package com.spaceaudits.stardeck

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface

/**
 * Records frames (with the HUD already drawn into them) to an H.264 mp4 in Movies/StarDeck.
 * Frame times are the times the frames are drawn.
 */
class HudRecorder(private val context: Context, width: Int, height: Int, fps: Int, val name: String) {
    // encoders want even sizes; multiples of 16 suit all of them
    private val w = width / 16 * 16
    private val h = height / 16 * 16
    private val uri: Uri
    private val pfd: android.os.ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface
    private var track = -1
    private var muxing = false
    private val info = MediaCodec.BufferInfo()

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StarDeck")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("could not create $name")
        pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("could not open $name")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    @Synchronized
    fun draw(frame: Bitmap) {
        val c = surface.lockHardwareCanvas()
        try {
            c.drawBitmap(frame, null, Rect(0, 0, w, h), null)
        } finally {
            surface.unlockCanvasAndPost(c)
        }
        drain(false)
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++idle > 100) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                i >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) muxer.writeSampleData(track, codec.getOutputBuffer(i)!!, info)
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finishes the file and makes it visible in the gallery. */
    @Synchronized
    fun stop() {
        runCatching {
            codec.signalEndOfInputStream()
            drain(true)
        }
        runCatching { codec.stop() }
        codec.release()
        surface.release()
        runCatching { if (muxing) muxer.stop() }
        muxer.release()
        pfd.close()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }
}
