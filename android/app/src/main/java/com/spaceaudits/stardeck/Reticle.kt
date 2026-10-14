package com.spaceaudits.stardeck

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/** Arcseconds per radian. */
const val ARCSEC_PER_RAD = 206264.806

/**
 * Drift rate of the stars past a fixed pointing at the celestial equator, in arcseconds per second
 * of time: 360° per sidereal day of 86164.0905 s (Urban & Seidelmann, 2013, Explanatory Supplement
 * to the Astronomical Almanac, 3rd ed., sec. 3.6). At declination δ the rate is this times cos δ.
 */
const val SIDEREAL_ARCSEC_PER_S = 360.0 * 3600.0 / 86164.0905

/** Sky angle of one camera pixel in arcseconds: pixel size over focal length, as a small angle. */
fun arcsecPerPixel(pixelUm: Double, focalMm: Double) = ARCSEC_PER_RAD * (pixelUm / 1000.0) / focalMm

/** Seconds for a star at declination `decDeg` to drift across `sizeArcsec` while the pointing is fixed. */
fun crossingSeconds(sizeArcsec: Double, decDeg: Double) = sizeArcsec / (SIDEREAL_ARCSEC_PER_S * abs(cos(Math.toRadians(decDeg))))

fun formatArcsec(a: Double): String = when {
    a >= 3600 -> "%.2f°".format(Locale.US, a / 3600)
    a >= 60 -> "%.2f′".format(Locale.US, a / 60)
    else -> "%.1f″".format(Locale.US, a)
}

/** Reticle settings; saved between runs by MainActivity. */
object Reticle {
    const val OFF = 0
    const val CROSS = 1
    const val CIRCLE = 2
    const val BOX = 3
    const val CROSS_CIRCLE = 4
    val SHAPES = listOf("Off", "Cross", "Circle", "Box", "Cross + circle")

    var shape by mutableStateOf(CROSS)
    /** diameter (circle) or side (box) in arcseconds */
    var sizeArcsec by mutableStateOf(600.0)
    /** two more rings at 2× and 3× the size */
    var rings by mutableStateOf(false)
    var label by mutableStateOf(true)
}

/**
 * Draws the reticle centred in a picture rectangle of `w` × `h`. `pxPerArcsec` converts sky angle to
 * drawing units; a null scale (camera data not read yet) falls back to a plain crosshair.
 */
fun reticleShapes(w: Float, h: Float, pxPerArcsec: Double?, draw: (kind: String, cx: Float, cy: Float, a: Float, b: Float) -> Unit) {
    val (cx, cy) = w / 2 to h / 2
    val shape = Reticle.shape
    if (shape == Reticle.OFF) return
    val arm = minOf(w, h) / 12
    val gap = minOf(w, h) / 60
    if (shape == Reticle.CROSS || shape == Reticle.CROSS_CIRCLE || pxPerArcsec == null) {
        draw("line", cx - arm, cy, cx - gap, cy)
        draw("line", cx + gap, cy, cx + arm, cy)
        draw("line", cx, cy - arm, cx, cy - gap)
        draw("line", cx, cy + gap, cx, cy + arm)
    }
    if (pxPerArcsec == null) return
    val size = (Reticle.sizeArcsec * pxPerArcsec).toFloat()
    val counts = if (Reticle.rings) listOf(1f, 2f, 3f) else listOf(1f)
    for (k in counts) {
        when (shape) {
            Reticle.CIRCLE, Reticle.CROSS_CIRCLE -> draw("circle", cx, cy, size * k / 2, 0f)
            Reticle.BOX -> draw("box", cx, cy, size * k / 2, size * k / 2)
        }
    }
}

private val reticleColor = Color(0xFFFF3C3C)

fun DrawScope.drawReticle(pxPerArcsec: Double?) {
    reticleShapes(size.width, size.height, pxPerArcsec) { kind, a, b, c, d ->
        when (kind) {
            "line" -> drawLine(reticleColor, Offset(a, b), Offset(c, d), 3f)
            "circle" -> drawCircle(reticleColor, c, Offset(a, b), style = Stroke(2.5f))
            "box" -> drawRect(reticleColor, Offset(a - c, b - d), Size(2 * c, 2 * d), style = Stroke(2.5f))
        }
    }
}

/** The same reticle drawn into a saved frame. */
fun burnReticle(canvas: AndroidCanvas, w: Int, h: Int, pxPerArcsec: Double?) {
    val stroke = (h / 400f).coerceAtLeast(2f)
    val paint = Paint().apply { color = android.graphics.Color.rgb(255, 60, 60); strokeWidth = stroke; style = Paint.Style.STROKE; isAntiAlias = true }
    reticleShapes(w.toFloat(), h.toFloat(), pxPerArcsec) { kind, a, b, c, d ->
        when (kind) {
            "line" -> canvas.drawLine(a, b, c, d, paint)
            "circle" -> canvas.drawCircle(a, b, c, paint)
            "box" -> canvas.drawRect(a - c, b - d, a + c, b + d, paint)
        }
    }
}

/** One stopwatch measurement of a crossing. */
class Crossing2(val startUtc: Long, val seconds: Double, val sizeArcsec: Double, val expected: Double?)

object TransitTimer {
    var startedAt by mutableStateOf<Long?>(null)
    val laps = mutableStateListOf<Crossing2>()
}

/**
 * Reticle panel: shape, size in sky angle, field of view, the expected time for a star to cross the
 * reticle at the current declination, and a stopwatch to time a crossing.
 */
@Composable
fun ReticlePanel(arcsecPerVideoPx: Double?, fieldArcsec: Pair<Double, Double>?, decDeg: Double?, utcMillis: () -> Long, onChange: () -> Unit) {
    var custom by androidx.compose.runtime.remember { mutableStateOf("") }
    var inArcmin by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Reticle.SHAPES.forEachIndexed { i, label ->
                Chip(label, Reticle.shape == i) { Reticle.shape = i; onChange() }
            }
        }
        Text("Size  ${formatArcsec(Reticle.sizeArcsec)}", color = Color.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (a in listOf(30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1200.0, 1800.0)) {
                Chip(formatArcsec(a), Reticle.sizeArcsec == a) { Reticle.sizeArcsec = a; onChange() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(custom, { custom = it }, Modifier.width(130.dp), singleLine = true, label = { Text(if (inArcmin) "custom, ′" else "custom, ″") })
            Chip("″", !inArcmin) { inArcmin = false }
            Chip("′", inArcmin) { inArcmin = true }
            Chip("Set", false) {
                custom.toDoubleOrNull()?.takeIf { it > 0 }?.let { Reticle.sizeArcsec = if (inArcmin) it * 60 else it; onChange() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(Reticle.rings, { Reticle.rings = it; onChange() }); Text("rings at 2× and 3×", color = Color.White, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(Reticle.label, { Reticle.label = it; onChange() }); Text("size and crossing time on the HUD", color = Color.White, fontSize = 13.sp)
        }

        val mono = Modifier
        if (arcsecPerVideoPx != null && fieldArcsec != null) {
            Text("Field ${formatArcsec(fieldArcsec.first)} × ${formatArcsec(fieldArcsec.second)}   ${"%.2f".format(Locale.US, arcsecPerVideoPx)}″ per pixel",
                mono, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        } else {
            Text("Waiting for the camera's focal length and pixel size from the scope.", color = Color.White, fontSize = 12.sp)
        }
        val expected = decDeg?.let { crossingSeconds(Reticle.sizeArcsec, it) }
        if (expected != null) {
            Text("A star at Dec ${fmtDeg(decDeg, Prefs.dms)} crosses ${formatArcsec(Reticle.sizeArcsec)} in %.2f s (pointing held still)".format(Locale.US, expected),
                color = Color.White, fontSize = 12.sp)
        }

        // stopwatch
        val started = TransitTimer.startedAt
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (started == null) "Start timing" else "Stop", started != null) {
                val now = utcMillis()
                if (started == null) TransitTimer.startedAt = now
                else {
                    TransitTimer.laps.add(0, Crossing2(started, (now - started) / 1000.0, Reticle.sizeArcsec, expected))
                    TransitTimer.startedAt = null
                }
            }
            if (started != null) Text("%.2f s".format(Locale.US, (utcMillis() - started) / 1000.0), color = Color(0xFFFF8A80), fontFamily = FontFamily.Monospace)
            if (TransitTimer.laps.isNotEmpty()) Chip("Clear", false) { TransitTimer.laps.clear() }
        }
        for (lap in TransitTimer.laps.take(8)) {
            val start = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(lap.startUtc))
            Text(
                "$start UTC  %.2f s over %s".format(Locale.US, lap.seconds, formatArcsec(lap.sizeArcsec)) +
                    (lap.expected?.let { "  (expected %.2f s)".format(Locale.US, it) } ?: ""),
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color(0x88FFFFFF) else Color(0x44FFFFFF), RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(label, color = Color.White, fontSize = 12.sp) }
}
