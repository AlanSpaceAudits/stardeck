package com.spaceaudits.stardeck

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AlbumTest {
    // replies recorded from an S50, firmware 8.46
    private val root = """{"path":"MyWorks","list":[
        {"group_name":"Scenery","files":[{"name":"Scenery_video","thn":"Scenery_video/2026-10-12-173238-Scenery-timelapse_thn.jpg","count":9,"type":0}]},
        {"group_name":"DeepSky","files":[{"name":"Unknown","thn":"Unknown/Stacked_9_Unknown_10.0s_LP_20250710-235042_thn.jpg","count":1,"type":0}]}]}"""
    private val folder = """{"path":"MyWorks/Scenery_video","list":[{"group_name":"2026-05-31","files":[
        {"name":"2026-05-31-232348-Scenery.mp4","thn":"2026-05-31-232348-Scenery_thn.jpg","type":5,"date":"2026-05-31 23:24:40","duration_sec":52.002998,"is_favorite":false},
        {"name":"Stacked_9_Unknown_10.0s_LP_20250710-235042.fit","thn":"Stacked_9_Unknown_10.0s_LP_20250710-235042_thn.jpg","type":2,"date":"2025-07-11 00:50:43"}]}]}"""

    @Test
    fun parsesRootFolders() {
        val items = parseAlbums(JSONObject(root))
        assertEquals(2, items.size)
        val a = items[0]
        assertTrue(a.isFolder)
        assertEquals("Scenery", a.group)
        assertEquals("MyWorks/Scenery_video", a.path)
        assertEquals("MyWorks/Scenery_video/2026-10-12-173238-Scenery-timelapse_thn.jpg", a.thumbPath)
        assertEquals(9, a.count)
        assertNull(a.durationSec)
    }

    @Test
    fun parsesFiles() {
        val items = parseAlbums(JSONObject(folder))
        val video = items[0]
        assertFalse(video.isFolder)
        assertEquals("MyWorks/Scenery_video/2026-05-31-232348-Scenery.mp4", video.path)
        assertEquals("MyWorks/Scenery_video/2026-05-31-232348-Scenery_thn.jpg", video.thumbPath)
        assertEquals(52.003, video.durationSec!!, 0.001)
        assertEquals("2026-05-31 23:24:40", video.date)
        assertEquals("video", albumTypeLabel(video.type))
        assertNull(video.count)
        assertTrue(items[1].isFits)
        assertEquals("stacked FITS", albumTypeLabel(items[1].type))
    }

    @Test
    fun emptyAndMissingReplies() {
        assertTrue(parseAlbums(null).isEmpty())
        assertTrue(parseAlbums(JSONObject("""{"path":"MyWorks/x","list":[]}""")).isEmpty())
    }

    @Test
    fun urlEncodesEachSegment() {
        assertEquals("http://192.168.0.79/MyWorks/M%2031/a%20b.fit", albumUrl("192.168.0.79", "MyWorks/M 31/a b.fit"))
        assertEquals("http://10.0.0.1/MyWorks/x.jpg", albumUrl("10.0.0.1", "/MyWorks/x.jpg"))
    }

    @Test
    fun formatsSizes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("195 KB", formatBytes(199462))
        assertEquals("11.9 MB", formatBytes(12450240))
        assertEquals("?", formatBytes(-1))
    }

    @Test
    fun mimeTypes() {
        assertEquals("image/jpeg", albumMime("a.JPG"))
        assertEquals("video/mp4", albumMime("a.mp4"))
        assertEquals("application/fits", albumMime("a.fit"))
        assertEquals("application/octet-stream", albumMime("a"))
    }

    private fun header(length: Int, dataType: Int, id: Int, imgType: Int = 4): ByteArray {
        val b = ByteBuffer.allocate(FRAME_HEADER_SIZE)
        b.putShort(0, FRAME_MAGIC.toShort())
        b.putInt(6, length)
        b.put(13, imgType.toByte())
        b.put(14, dataType.toByte())
        b.put(15, id.toByte())
        return b.array()
    }

    @Test
    fun parsesRecordedHeader() {
        // first 24 bytes of a get_img_thumbnail reply header from the scope; the rest are zero
        val hex = "03c30002005000004edbffd9000402630000000000000000"
        val h = ByteArray(FRAME_HEADER_SIZE).also { a -> hex.chunked(2).forEachIndexed { i, s -> a[i] = s.toInt(16).toByte() } }
        val f = parseFrameHeader(h)
        assertEquals(FRAME_MAGIC, f.magic)
        assertEquals(20187, f.length)
        assertEquals(4, f.imgType)
        assertEquals(2, f.dataType)
        assertEquals(99, f.id)
        assertFalse(f.isHeartbeat)
    }

    @Test
    fun readsReplyAfterHeartbeatAndStatusFrames() {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val out = ByteArrayOutputStream()
        out.write(header(3, 0, 1)); out.write(byteArrayOf(9, 9, 9))
        out.write(header(4, 0, 99)); out.write(byteArrayOf(0, 0, 0, 0))
        out.write(header(payload.size, 2, 99)); out.write(payload)
        assertArrayEquals(payload, readFileReply(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun unzipsDataType3() {
        val payload = "fits as jpg".toByteArray()
        val zip = ByteArrayOutputStream().also { bo -> ZipOutputStream(bo).use { it.putNextEntry(ZipEntry("x.jpg")); it.write(payload); it.closeEntry() } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(header(zip.size, 3, 99)); out.write(zip)
        assertArrayEquals(payload, readFileReply(ByteArrayInputStream(out.toByteArray())))
    }

    @Test(expected = IOException::class)
    fun dataType1IsFailure() {
        val out = ByteArrayOutputStream()
        out.write(header(2, 1, 99)); out.write(byteArrayOf(0, 0))
        readFileReply(ByteArrayInputStream(out.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun badMagicIsRejected() {
        val h = header(0, 2, 99).also { it[0] = 0 }
        readFileReply(ByteArrayInputStream(h))
    }

    @Test
    fun requestLine() {
        val line = fileRequestLine("get_img_thumbnail", "MyWorks/a.fit")
        assertTrue(line.endsWith("\r\n"))
        val o = JSONObject(line.trim())
        assertEquals(99, o.getInt("id"))
        assertEquals("get_img_thumbnail", o.getString("method"))
        assertEquals("MyWorks/a.fit", o.getJSONObject("params").getString("name"))
    }
}
