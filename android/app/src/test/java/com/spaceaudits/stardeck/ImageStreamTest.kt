package com.spaceaudits.stardeck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImageStreamTest {
    private fun header(length: Int, imgType: Int, dataType: Int, id: Int, w: Int, h: Int, bigEndian: Boolean = false) =
        ByteArray(FrameHeader.LEN).also { b ->
            b[0] = 0x03; b[1] = 0xC3.toByte()
            for (i in 0 until 4) b[6 + i] = (length shr (24 - 8 * i)).toByte()
            b[12] = if (bigEndian) 1 else 0
            b[13] = imgType.toByte(); b[14] = dataType.toByte(); b[15] = id.toByte()
            b[16] = (w shr 8).toByte(); b[17] = w.toByte()
            b[18] = (h shr 8).toByte(); b[19] = h.toByte()
        }

    private fun le16(vararg v: Int) = ByteArray(v.size * 2).also { b ->
        v.forEachIndexed { i, x -> b[2 * i] = x.toByte(); b[2 * i + 1] = (x shr 8).toByte() }
    }

    @Test
    fun headerParse() {
        val h = FrameHeader.parse(header(8, 1, 0, 2, 2, 2))
        assertEquals(FrameHeader(8, false, 1, 0, 2, 2, 2), h)
        val big = FrameHeader.parse(header(1920 * 1080 * 2, 1, 3, 2, 1080, 1920, bigEndian = true))
        assertEquals(1920 * 1080 * 2, big.length)
        assertEquals(1080 to 1920, big.width to big.height)
        assertTrue(big.bigEndian)
        val bad = header(8, 1, 0, 2, 2, 2).also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) { FrameHeader.parse(bad) }
    }

    @Test
    fun debayerGrbg() {
        // GRBG cell: G=10 R=20 / B=30 G=50
        val rgb = debayer(intArrayOf(10, 20, 30, 50), 2, 2, 2)
        assertEquals(1 to 1, rgb.width to rgb.height)
        assertArrayEquals(intArrayOf(20, 30, 30), rgb.data)
        // RGGB: R=10 G=20 / G=30 B=50
        assertArrayEquals(intArrayOf(10, 25, 50), debayer(intArrayOf(10, 20, 30, 50), 2, 2, 0).data)
    }

    @Test
    fun bayerFrameDecodes() {
        val payload = le16(10, 20, 30, 50, 10, 20, 30, 50, 0, 0, 0, 0, 0, 0, 0, 0)
        val d = decode(FrameHeader(payload.size, false, 1, 0, 2, 4, 4), payload) as Decoded.Pixels
        assertEquals(2 to 2, d.width to d.height)
        assertEquals(4, d.argb.size)
        assertTrue(d.argb.all { it ushr 24 == 0xFF })
        assertTrue(d.info.startsWith("bayer16"))
    }

    @Test
    fun zipFrameUsesFirstEntry() {
        val raw = le16(10, 20, 30, 50)
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("raw_data")); z.write(raw); z.closeEntry()
                z.putNextEntry(ZipEntry("other")); z.write(ByteArray(3)); z.closeEntry()
            }
        }.toByteArray()
        val d = decode(FrameHeader(zip.size, false, 1, 3, 2, 2, 2), zip) as Decoded.Pixels
        assertEquals(1 to 1, d.width to d.height)
        assertTrue(d.info.contains("8 bytes"))
    }

    @Test
    fun rgb48AndFlatChannel() {
        // one pixel, every channel flat: shown at its true level
        val payload = le16(65535, 0, 32768)
        val d = decode(FrameHeader(payload.size, false, 5, 0, 2, 1, 1), payload) as Decoded.Pixels
        assertEquals(0xFF000000.toInt() or (255 shl 16) or (0 shl 8) or 180, d.argb[0])
        assertTrue(d.info.startsWith("rgb48"))
    }

    @Test
    fun jpegIsPassedThrough() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
        assertTrue(decode(FrameHeader(4, false, 5, 0, 2, 0, 0), jpeg) is Decoded.Jpeg)
    }

    @Test
    fun textPayloadIsNoImage() {
        // header + payload as sent by the scope (live capture) when it has no image
        val frame = ByteArray(FrameHeader.LEN) + "there is no image now".toByteArray()
        frame[0] = 0x03; frame[1] = 0xC3.toByte(); frame[3] = 0x02; frame[5] = 0x50; frame[9] = 0x15
        frame[10] = 0x74; frame[11] = 0x77; frame[14] = 0x01; frame[15] = 0x02
        val h = FrameHeader.parse(frame)
        assertEquals(FrameHeader(21, false, 0, 1, 2, 0, 0), h)
        val e = assertThrows(NoImageException::class.java) { decode(h, frame.copyOfRange(FrameHeader.LEN, frame.size)) }
        assertEquals("there is no image now", e.message)
    }

    @Test
    fun unknownLayoutFails() {
        assertThrows(IllegalArgumentException::class.java) { decode(FrameHeader(6, false, 1, 0, 2, 2, 2), ByteArray(6)) }
    }
}
