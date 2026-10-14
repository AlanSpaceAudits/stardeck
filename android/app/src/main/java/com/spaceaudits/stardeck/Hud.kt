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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** App preferences that several screens read. Loaded and saved by MainActivity. */
object Prefs {
    /** Angles as degrees, minutes, seconds instead of decimal degrees. */
    var dms by mutableStateOf(false)
}

/** Measured frames per second of the picture on screen; null when unknown. */
object LiveStats {
    var fps by mutableStateOf<Double?>(null)
}

/** File name in the official app's style: Unix epoch seconds plus the extension. */
fun epochName(utcMillis: Long, ext: String) =
    Labels.current.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").let { if (it.isEmpty()) "" else "${it}_" } + "${utcMillis / 1000}.$ext"

/** Optional name put in front of saved file names and on the HUD, for targets the catalogue does not know. */
object Labels {
    var current by mutableStateOf("")
}

/** The data block shown on the HUD and burned into saved frames and recordings. */
fun hudLines(pointing: JSONObject?, gps: Gps): List<String> {
    fun v(k: String) = pointing?.optDouble(k)?.takeIf { !it.isNaN() }?.let { fmtDeg(it, Prefs.dms, signed = k == "alt") } ?: "--"
    val fix = gps.fix
    val fps = LiveStats.fps?.let { "  %.1f fps".format(Locale.US, it) } ?: ""
    val dec = pointing?.optDouble("dec")?.takeIf { !it.isNaN() }
    val sized = Reticle.shape == Reticle.CIRCLE || Reticle.shape == Reticle.BOX || Reticle.shape == Reticle.CROSS_CIRCLE
    val reticle = if (Reticle.label && sized) listOf(
        "Reticle ${formatArcsec(Reticle.sizeArcsec)}" + (dec?.let { "  crossing %.2f s".format(Locale.US, crossingSeconds(Reticle.sizeArcsec, it)) } ?: ""),
    ) else emptyList()
    val label = Labels.current.trim().takeIf { it.isNotEmpty() }?.let { listOf("Target $it") } ?: emptyList()
    return label + reticle + listOf(
        "Az ${v("az")}  El ${v("alt")}",
        if (fix != null) "Lat ${fmtDeg(fix.latitude, Prefs.dms, digits = 5)}  Lon ${fmtDeg(fix.longitude, Prefs.dms, digits = 5)}" else "Lat --  Lon --",
        utcFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(gps.utcMillis())) + " UTC" + fps,
    )
}

/** Flips the frame as displayed, then draws the crosshair and data block into it. */
fun burnHud(frame: Bitmap, flipH: Boolean, flipV: Boolean, lines: List<String>?, reticlePxPerArcsec: Double? = null): Bitmap {
    val m = Matrix().apply { preScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f) }
    val out = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, false).copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(out)
    // the reticle is scaled to sky angle: drawing units per arcsecond of this frame
    burnReticle(c, out.width, out.height, reticlePxPerArcsec)
    if (lines == null) return out
    val (w, h) = out.width.toFloat() to out.height.toFloat()
    val text = Paint().apply { color = Color.WHITE; typeface = Typeface.MONOSPACE; textSize = (h / 45).coerceAtLeast(12f); isAntiAlias = true }
    val lineH = text.fontSpacing
    val pad = lineH / 3
    val top = h - pad * 2 - lineH * lines.size
    c.drawRect(pad, top - pad, pad * 3 + lines.maxOf { text.measureText(it) }, h - pad, Paint().apply { color = Color.argb(165, 0, 0, 0) })
    lines.forEachIndexed { i, line -> c.drawText(line, pad * 2, top + lineH * i - text.ascent(), text) }
    return out
}

/** A file the app saved on the phone. */
class Saved(val name: String, val uri: android.net.Uri)

/** Saves into Pictures/StarDeck. */
fun savePng(context: Context, bitmap: Bitmap, utcMillis: Long): Saved {
    val name = epochName(utcMillis, "png")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StarDeck")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("could not create $name")
    context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return Saved(name, uri)
}
