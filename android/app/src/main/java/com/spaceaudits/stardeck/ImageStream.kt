package com.spaceaudits.stardeck

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.pow

/** Image channel (TCP 4800 tele, 4804 wide): 80-byte big-endian header + payload. */
data class FrameHeader(
    val length: Int,
    val bigEndian: Boolean,
    val imgType: Int,
    val dataType: Int,
    val id: Int,
    val width: Int,
    val height: Int,
    /** stacked-image number, used to ask for its annotation */
    val imageId: Int = 0,
) {
    companion object {
        const val LEN = 80
        const val MAGIC = 0x03C3

        fun parse(b: ByteArray): FrameHeader {
            require(b.size >= LEN) { "short frame header" }
            fun u8(o: Int) = b[o].toInt() and 0xFF
            fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
            require(u16(0) == MAGIC) { "bad frame magic 0x%04x".format(u16(0)) }
            val length = (u8(6) shl 24) or (u8(7) shl 16) or (u8(8) shl 8) or u8(9)
            require(length >= 0) { "negative frame length" }
            return FrameHeader(length, u8(12) != 0, u8(13), u8(14), u8(15), u16(16), u16(18), u16(28))
        }
    }
}

/** Display settings that can change between frames. */
data class Knobs(
    /** Bayer layout: 0 RGGB, 1 BGGR, 2 GRBG, 3 GBRG. */
    val bayer: Int = 2,
    val gamma: Float = 0.5f,
    val swapRb: Boolean = false,
)

sealed interface Decoded {
    val info: String

    /** 8-bit pixels as ARGB ints, row by row. */
    class Pixels(val width: Int, val height: Int, val argb: IntArray, override val info: String) : Decoded

    /** A JPEG file; decoding it needs the platform. */
    class Jpeg(val bytes: ByteArray, override val info: String) : Decoded
}

/** The scope answers with plain text, such as "there is no image now", when it has nothing to send. */
class NoImageException(text: String) : Exception(text)

/** Interleaved 16-bit RGB (r, g, b per pixel). */
class Rgb16(val width: Int, val height: Int, val data: IntArray)

/** 16-bit Bayer mosaic to half-resolution RGB (one output pixel per 2x2 cell). */
fun debayer(px: IntArray, w: Int, h: Int, bayer: Int): Rgb16 {
    // index of R within the 2x2 cell (row*2 + col); B is diagonally opposite
    val r = intArrayOf(0, 3, 1, 2)[bayer and 3]
    val b = 3 - r
    val (ow, oh) = w / 2 to h / 2
    val out = IntArray(ow * oh * 3)
    val c = IntArray(4)
    var o = 0
    for (y in 0 until oh) {
        for (x in 0 until ow) {
            c[0] = px[2 * y * w + 2 * x]
            c[1] = px[2 * y * w + 2 * x + 1]
            c[2] = px[(2 * y + 1) * w + 2 * x]
            c[3] = px[(2 * y + 1) * w + 2 * x + 1]
            var g = 0
            for (i in 0 until 4) if (i != r && i != b) g += c[i]
            out[o++] = c[r]
            out[o++] = g / 2
            out[o++] = c[b]
        }
    }
    return Rgb16(ow, oh, out)
}

/** Per-channel percentile stretch plus gamma, to ARGB. */
fun stretch(rgb: IntArray, gamma: Float): IntArray {
    val n = rgb.size / 3
    val luts = Array(3) { ch ->
        val hist = IntArray(65536)
        for (i in 0 until n) hist[rgb[i * 3 + ch]]++
        fun pct(frac: Double): Int {
            val target = (n * frac).toLong()
            var acc = 0L
            for (v in 0 until 65536) {
                acc += hist[v]
                if (acc > target) return v
            }
            return 65535
        }
        var lo = pct(0.01)
        var hi = pct(0.999)
        // a flat (e.g. saturated) channel has no range to stretch; show it at its true level
        if (hi < lo + 256) { lo = 0; hi = 65535 }
        val span = (maxOf(hi, lo + 1) - lo).toFloat()
        IntArray(65536) { v -> (((v - lo) / span).coerceIn(0f, 1f).pow(gamma) * 255f).toInt() }
    }
    val (lr, lg, lb) = luts
    return IntArray(n) { i -> (0xFF shl 24) or (lr[rgb[i * 3]] shl 16) or (lg[rgb[i * 3 + 1]] shl 8) or lb[rgb[i * 3 + 2]] }
}

private fun firstZipEntry(payload: ByteArray): ByteArray = ZipInputStream(payload.inputStream()).use { z ->
    z.nextEntry ?: throw IllegalArgumentException("empty ZIP frame")
    z.readBytes()
}

/** Decodes one image frame. Throws [NoImageException] for a text answer. */
fun decode(h: FrameHeader, payload: ByteArray, k: Knobs = Knobs()): Decoded {
    val data = if (h.dataType == 3 && h.id != 4) firstZipEntry(payload) else payload
    val tag = "type ${h.imgType} ${h.width}x${h.height} ${data.size} bytes"

    if (h.imgType == 4 || (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte())) {
        return Decoded.Jpeg(data, "jpeg $tag")
    }

    val n = h.width * h.height
    if (n == 0) throw NoImageException(String(data, Charsets.UTF_8).trim())
    fun words(): IntArray = IntArray(data.size / 2) { i ->
        val (a, b) = (data[2 * i].toInt() and 0xFF) to (data[2 * i + 1].toInt() and 0xFF)
        if (h.bigEndian) (a shl 8) or b else (b shl 8) or a
    }
    val (rgb, kind) = when (data.size) {
        n * 2 -> debayer(words(), h.width, h.height, k.bayer) to "bayer16"
        n * 6 -> Rgb16(h.width, h.height, words()) to "rgb48"
        else -> throw IllegalArgumentException("unknown pixel layout: $tag")
    }
    if (k.swapRb) {
        val d = rgb.data
        for (i in d.indices step 3) d[i] = d[i + 2].also { d[i + 2] = d[i] }
    }
    return Decoded.Pixels(rgb.width, rgb.height, stretch(rgb.data, k.gamma), "$kind $tag")
}

fun Decoded.toBitmap(): Bitmap = when (this) {
    is Decoded.Pixels -> Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    is Decoded.Jpeg -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("bad JPEG frame")
}

/**
 * Client for one image port. Commands: "begin_streaming", "stop_streaming", "get_current_img",
 * "get_stacked_img". The newest frame and its description are Compose state.
 */
class ImageStreamClient(private val port: Int = 4800) {
    var frame by mutableStateOf<Bitmap?>(null)
    /** img_type of [frame]: 1 preview, 4 JPEG, 5 stacked. */
    var frameType by mutableStateOf(0)
    /** image id of [frame] (header bytes 28-29) */
    var frameId by mutableStateOf(0)
    var info by mutableStateOf("")
    var status by mutableStateOf("not connected")
    var connected by mutableStateOf(false)
    var streaming by mutableStateOf(false)
    @Volatile var knobs = Knobs()

    @Volatile private var socket: Socket? = null
    @Volatile private var host: String? = null
    @Volatile private var queue = LinkedBlockingQueue<String>()
    @Volatile private var generation = 0
    /** True from connect() until the channel fails or is closed. */
    @Volatile private var alive = false

    fun connect(ip: String) {
        close()
        val gen = generation
        val q = queue
        host = ip
        alive = true
        status = "connecting to $ip:$port"
        thread(name = "image-read") {
            try {
                val s = Socket().apply {
                    connect(InetSocketAddress(ip, this@ImageStreamClient.port), 5000)
                    tcpNoDelay = true
                    soTimeout = 15000 // heartbeat replies arrive every few seconds
                }
                if (gen != generation) return@thread s.close()
                socket = s
                connected = true
                status = "connected to $ip:$port"
                thread(name = "image-write") { writeLoop(s, q, gen) }
                val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
                val hb = ByteArray(FrameHeader.LEN)
                while (gen == generation) {
                    input.readFully(hb)
                    val h = FrameHeader.parse(hb)
                    val payload = ByteArray(h.length)
                    input.readFully(payload)
                    // id 1 = heartbeat reply; 4-byte payloads are status words, not images
                    if (h.id == 1 || h.length <= 4) continue
                    try {
                        val d = decode(h, payload, knobs)
                        val bmp = d.toBitmap()
                        if (gen != generation) break
                        frame = bmp
                        frameType = h.imgType
                        frameId = h.imageId
                        info = d.info
                    } catch (e: NoImageException) {
                        status = e.message ?: "no image"
                    } catch (e: IllegalArgumentException) {
                        status = e.message ?: "bad frame"
                    }
                }
            } catch (e: Exception) {
                if (gen == generation) {
                    alive = false
                    connected = false
                    streaming = false
                    status = "image channel: ${e.message}"
                    runCatching { socket?.close() }
                }
            }
        }
    }

    private fun writeLoop(s: Socket, q: LinkedBlockingQueue<String>, gen: Int) {
        try {
            val out = s.getOutputStream()
            while (gen == generation) {
                val method = q.poll(4, TimeUnit.SECONDS)
                val line = if (method != null) "{\"method\": \"$method\", \"id\": 2}\r\n" else "{\"method\": \"test_connection\", \"id\": 1}\r\n"
                out.write(line.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            runCatching { s.close() } // wakes the reader, which reports the error
        }
    }

    /** Sends a command; reconnects first when the channel has dropped. */
    fun command(method: String) {
        if (!alive) host?.let { connect(it) }
        when (method) {
            "begin_streaming" -> streaming = true
            "stop_streaming" -> streaming = false
        }
        queue.offer(method)
    }

    fun close() {
        generation++
        alive = false
        queue = LinkedBlockingQueue()
        runCatching { socket?.close() }
        socket = null
        connected = false
        streaming = false
        status = "not connected"
    }
}
