package com.spaceaudits.stardeck

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** The data block shown on the HUD and burned into saved frames. */
fun hudLines(pointing: JSONObject?, gps: Gps): List<String> {
    fun v(k: String, digits: Int) = pointing?.optDouble(k)?.takeIf { !it.isNaN() }?.let { "%.${digits}f°".format(Locale.US, it) } ?: "--"
    val fix = gps.fix
    val locked = gps.locked()
    return listOf(
        "Az ${v("az", 4)}  El ${v("alt", 4)}",
        if (fix != null) "Lat %.5f°  Lon %.5f°  ±%.0f m".format(Locale.US, fix.latitude, fix.longitude, fix.accuracy) else "Lat --  Lon --",
        utcFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(gps.utcMillis())) + if (locked) " UTC (GPS)" else " UTC (phone clock)",
    )
}

/** Flips the frame as displayed, then draws the crosshair and data block into it. */
fun burnHud(frame: Bitmap, flipH: Boolean, flipV: Boolean, lines: List<String>?): Bitmap {
    val m = Matrix().apply { preScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f) }
    val out = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, false).copy(Bitmap.Config.ARGB_8888, true)
    if (lines == null) return out
    val c = Canvas(out)
    val (w, h) = out.width.toFloat() to out.height.toFloat()
    val red = Paint().apply { color = Color.rgb(255, 60, 60); strokeWidth = (h / 400).coerceAtLeast(2f) }
    val (arm, gap) = h / 20 to h / 100
    c.drawLine(w / 2 - arm, h / 2, w / 2 - gap, h / 2, red)
    c.drawLine(w / 2 + gap, h / 2, w / 2 + arm, h / 2, red)
    c.drawLine(w / 2, h / 2 - arm, w / 2, h / 2 - gap, red)
    c.drawLine(w / 2, h / 2 + gap, w / 2, h / 2 + arm, red)
    val text = Paint().apply { color = Color.WHITE; typeface = Typeface.MONOSPACE; textSize = (h / 45).coerceAtLeast(12f); isAntiAlias = true }
    val lineH = text.fontSpacing
    val pad = lineH / 3
    val top = h - pad * 2 - lineH * lines.size
    c.drawRect(pad, top - pad, pad * 3 + lines.maxOf { text.measureText(it) }, h - pad, Paint().apply { color = Color.argb(165, 0, 0, 0) })
    lines.forEachIndexed { i, line -> c.drawText(line, pad * 2, top + lineH * i - text.ascent(), text) }
    return out
}

/** Saves into Pictures/StarDeck; returns the file name. */
fun savePng(context: Context, bitmap: Bitmap, prefix: String, utcMillis: Long): String {
    val name = prefix + utcFormat("yyyyMMdd_HHmmss_SSS").format(Date(utcMillis)) + ".png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StarDeck")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("could not create $name")
    context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return name
}
