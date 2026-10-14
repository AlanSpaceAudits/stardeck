package com.spaceaudits.stardeck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Exposure time for display: µs below 1 ms, s from 1 s, else ms. */
fun formatExposureMs(ms: Double): String = when {
    ms < 1.0 -> "%.0f µs".format(Locale.US, ms * 1000)
    ms >= 1000.0 -> "%.2f s".format(Locale.US, ms / 1000)
    else -> "%.2f ms".format(Locale.US, ms)
}

/** Slider position 0..1 to exposure on a logarithmic scale between `minMs` and `maxMs`, and back. */
fun exposureFromPosition(p: Float, minMs: Double, maxMs: Double) = minMs * (maxMs / minMs).pow(p.toDouble())
fun positionFromExposure(ms: Double, minMs: Double, maxMs: Double) = (ln(ms / minMs) / ln(maxMs / minMs)).toFloat().coerceIn(0f, 1f)

/**
 * Exposure panel for the scenery and solar views, like the official adjust panel:
 * Auto with a brightness bias, or Manual with exposure time and gain.
 */
@Composable
fun AdjustPanel(link: Link, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = link.setting
    val manual = s?.optBoolean("manual_exp") ?: false
    val range = s?.optJSONArray("isp_range_exp_us_scenery") ?: s?.optJSONArray("isp_range_exp_us")
    val minMs = (range?.optDouble(0) ?: 30.0) / 1000.0
    val maxMs = (range?.optDouble(1) ?: 1_000_000.0) / 1000.0
    val gainRange = s?.optJSONArray("isp_range_gain")
    val gainMax = (gainRange?.optDouble(1) ?: 400.0).toFloat()

    // the scope reports -999000 / -9990 while exposure is automatic
    var expPos by remember { mutableFloatStateOf(0.5f) }
    var gain by remember { mutableFloatStateOf(80f) }
    var bias by remember { mutableFloatStateOf(0f) } // -100..100; ae_bri_percent = 50 + bias / 2
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(s) {
        if (s == null || synced) return@LaunchedEffect
        s.optDouble("isp_exp_ms").takeIf { it > 0 }?.let { expPos = positionFromExposure(it, minMs, maxMs) }
        s.optDouble("isp_gain").takeIf { it >= 0 }?.let { gain = it.toFloat() }
        s.optDouble("ae_bri_percent").takeIf { !it.isNaN() }?.let { bias = ((it - 50) * 2).toFloat() }
        synced = true
    }

    Column(
        modifier.width(320.dp).background(Color(0xE6101418), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Exposure", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
        }
        Row(Modifier.background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(3.dp)) {
            for ((label, value) in listOf("Auto" to false, "Manual" to true)) {
                Box(
                    Modifier.background(if (manual == value) Color(0x66FFFFFF) else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { link.send("set_setting", JSONObject().put("manual_exp", value)) }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) { Text(label, color = Color.White, fontSize = 13.sp) }
            }
        }
        if (!manual) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness  ${bias.roundToInt()}", color = Color.White, modifier = Modifier.weight(1f))
                Text("Reset", color = Color(0xFF90CAF9), modifier = Modifier.clickable {
                    bias = 0f
                    link.send("set_setting", JSONObject().put("ae_bri_percent", 50.0))
                }.padding(4.dp))
            }
            Slider(bias, { bias = it }, valueRange = -100f..100f,
                onValueChangeFinished = { link.send("set_setting", JSONObject().put("ae_bri_percent", 50.0 + bias / 2.0)) })
        } else {
            val ms = exposureFromPosition(expPos, minMs, maxMs)
            Text("Exposure  ${formatExposureMs(ms)}", color = Color.White)
            Slider(expPos, { expPos = it }, valueRange = 0f..1f,
                onValueChangeFinished = { link.send("set_setting", JSONObject().put("isp_exp_ms", exposureFromPosition(expPos, minMs, maxMs))) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, step) in listOf("÷2" to 0.5, "−⅓" to 0.7937, "+⅓" to 1.26, "×2" to 2.0)) {
                    Box(
                        Modifier.background(Color(0x55FFFFFF), RoundedCornerShape(12.dp)).clickable {
                            val next = (exposureFromPosition(expPos, minMs, maxMs) * step).coerceIn(minMs, maxMs)
                            expPos = positionFromExposure(next, minMs, maxMs)
                            link.send("set_setting", JSONObject().put("isp_exp_ms", next))
                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(label, color = Color.White, fontSize = 13.sp) }
                }
            }
            Text("Gain  ${gain.roundToInt()}", color = Color.White)
            Slider(gain, { gain = it }, valueRange = 0f..gainMax,
                onValueChangeFinished = { link.send("set_setting", JSONObject().put("isp_gain", gain.roundToInt().toDouble())) })
        }
        Spacer(Modifier.fillMaxWidth())
    }
}
