package com.spaceaudits.stardeck

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

/** One entry of a `get_albums` reply. `dir` is the reply's `path`; folders have type 0. */
data class AlbumItem(
    val dir: String,
    val group: String,
    val name: String,
    val thn: String,
    val type: Int,
    val date: String,
    val durationSec: Double?,
    val count: Int?,
) {
    val isFolder get() = type == 0
    val isFits get() = type in 1..4 || type == 11
    val path get() = "$dir/$name"
    /** Folder thumbnails name the subfolder too (`Scenery_video/..._thn.jpg`), so both kinds sit under `dir`. */
    val thumbPath get() = if (thn.isEmpty()) "" else "$dir/$thn"
}

/** Type codes from the official app's EnumAlbumCellType. */
fun albumTypeLabel(type: Int): String = when (type) {
    0 -> "folder"
    1 -> "FITS frame"
    2 -> "stacked FITS"
    3 -> "post-stacked FITS"
    4 -> "video-stacked FITS"
    5, 6 -> "video"
    7, 8 -> "time-lapse"
    10 -> "JPG"
    11 -> "metering FITS"
    else -> "file"
}

fun parseAlbums(result: JSONObject?): List<AlbumItem> {
    if (result == null) return emptyList()
    val dir = result.optString("path")
    val groups = result.optJSONArray("list") ?: return emptyList()
    val items = mutableListOf<AlbumItem>()
    for (g in 0 until groups.length()) {
        val group = groups.optJSONObject(g) ?: continue
        val files = group.optJSONArray("files") ?: continue
        for (f in 0 until files.length()) {
            val o = files.optJSONObject(f) ?: continue
            items += AlbumItem(
                dir, group.optString("group_name"), o.optString("name"), o.optString("thn"), o.optInt("type", 9), o.optString("date"),
                o.optDouble("duration_sec").takeIf { !it.isNaN() }, if (o.has("count")) o.optInt("count") else null,
            )
        }
    }
    return items
}

/** The scope serves the album over plain HTTP on port 80; each path segment is URL-encoded, spaces as %20. */
fun albumUrl(ip: String, path: String): String =
    "http://$ip/" + path.trimStart('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

fun formatBytes(n: Long): String = when {
    n < 0 -> "?"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.1f GB".format(n / 1073741824.0)
}

fun albumMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "mp4" -> "video/mp4"
    "avi" -> "video/x-msvideo"
    "fit", "fits" -> "application/fits"
    else -> "application/octet-stream"
}

/** File size from a HEAD request, or -1. */
fun albumSize(ip: String, path: String): Long = runCatching {
    val c = URL(albumUrl(ip, path)).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "HEAD"
        c.connectTimeout = 5000
        c.readTimeout = 5000
        if (c.responseCode == 200) c.contentLengthLong else -1L
    } finally {
        c.disconnect()
    }
}.getOrDefault(-1L)

fun albumBytes(ip: String, path: String): ByteArray {
    val c = URL(albumUrl(ip, path)).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 5000
        c.readTimeout = 10000
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}

/** Saves a stream to Download/StarDeck and returns the saved name. */
fun saveDownload(context: Context, name: String, input: InputStream, length: Long, onProgress: (Long, Long) -> Unit): String {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, albumMime(name))
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StarDeck")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("could not create $name")
    try {
        resolver.openOutputStream(uri)!!.use { out ->
            val buf = ByteArray(65536)
            var done = 0L
            var lastReport = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (System.currentTimeMillis() - lastReport > 200) {
                    lastReport = System.currentTimeMillis()
                    onProgress(done, length)
                }
            }
            onProgress(done, length)
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
    return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
}

/** Downloads an album file over HTTP into Download/StarDeck. */
fun downloadAlbumFile(context: Context, ip: String, item: AlbumItem, onProgress: (Long, Long) -> Unit): String {
    val c = URL(albumUrl(ip, item.path)).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 5000
        c.readTimeout = 30000
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
        return c.inputStream.use { saveDownload(context, item.name, it, c.contentLengthLong, onProgress) }
    } finally {
        c.disconnect()
    }
}

/** 80-byte big-endian frame header used on ports 4800, 4804 and 4801. */
data class FileFrameHeader(val magic: Int, val length: Int, val imgType: Int, val dataType: Int, val id: Int, val width: Int, val height: Int) {
    val isHeartbeat get() = id == 1
}

const val FRAME_MAGIC = 0x03C3
const val FRAME_HEADER_SIZE = 80

fun parseFrameHeader(h: ByteArray): FileFrameHeader {
    require(h.size >= FRAME_HEADER_SIZE) { "header too short" }
    val b = ByteBuffer.wrap(h) // big-endian
    return FileFrameHeader(
        b.getShort(0).toInt() and 0xFFFF, b.getInt(6), h[13].toInt() and 0xFF, h[14].toInt() and 0xFF, h[15].toInt() and 0xFF,
        b.getShort(16).toInt() and 0xFFFF, b.getShort(18).toInt() and 0xFFFF,
    )
}

/** One request on the file port; `id` 99 as in the official app. */
fun fileRequestLine(method: String, name: String): String =
    JSONObject().put("id", 99).put("method", method).put("params", JSONObject().put("name", name)).toString() + "\r\n"

/**
 * Reads frames until the reply to a file request: skips heartbeats and 4-byte status frames.
 * Data type 1 is a failure with a text payload; data type 3 is a ZIP whose first entry is the file.
 */
fun readFileReply(input: InputStream): ByteArray {
    val d = DataInputStream(input)
    val head = ByteArray(FRAME_HEADER_SIZE)
    while (true) {
        d.readFully(head)
        val h = parseFrameHeader(head)
        if (h.magic != FRAME_MAGIC || h.length < 0) throw IOException("bad frame header")
        val body = ByteArray(h.length)
        d.readFully(body)
        if (h.isHeartbeat || h.length == 4) continue
        if (h.dataType == 1) throw IOException("scope: ${String(body).trim()}")
        if (h.dataType == 3) {
            ZipInputStream(ByteArrayInputStream(body)).use { z ->
                z.nextEntry ?: throw IOException("empty archive")
                return z.readBytes()
            }
        }
        return body
    }
}

/** `get_img_file_in_jpg` (a FITS file rendered as JPG) or `get_img_thumbnail` over port 4801. */
fun fetchOnFilePort(ip: String, method: String, path: String): ByteArray =
    Socket().use { s ->
        s.connect(InetSocketAddress(ip, 4801), 5000)
        s.soTimeout = 60000
        s.getOutputStream().apply { write(fileRequestLine(method, path).toByteArray()); flush() }
        readFileReply(s.getInputStream())
    }
