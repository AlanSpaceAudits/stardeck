package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** `iscope_start_view` params for a star goto; coordinates of date. The mosaic object is sent only for a real mosaic. */
fun startViewParams(targetName: String, raHours: Double, decDeg: Double, lpFilter: Boolean, wideCam: Boolean, mosaic: Mosaic?): JSONObject {
    val p = JSONObject().put("mode", "star").put("selected_cam", if (wideCam) "SecondView" else "View")
        .put("target_name", targetName).put("target_ra_dec", JSONArray().put(raHours).put(decDeg)).put("lp_filter", lpFilter)
    mosaic?.takeIf { it.isMosaic }?.let { p.put("mosaic", it.toJson()) }
    return p
}

/** Goto with mosaic framing. Telescope camera: scale 1.0 to 2.0 in 0.1 steps, angle -90 to 90°; wide camera: angle 0 or 90°. */
@Composable
fun MosaicPanel(link: Link, catalog: Catalog) {
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<SkyObject?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var angle by remember { mutableFloatStateOf(0f) }
    var lp by remember { mutableStateOf(false) }
    var wide by remember { mutableStateOf(false) }
    val current = Mosaic.fromJson(link.setting?.optJSONObject("mosaic"))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        current?.let {
            Text("scope mosaic setting %.1fx %.0f°".format(it.scale, it.angle) + if (it.isMosaic) "" else " (off)", fontFamily = FontFamily.Monospace)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("target: M 31, NGC 7000") })
        for (o in catalog.search(query, 5)) {
            Button({ target = o; query = "" }, Modifier.fillMaxWidth()) { Text("${o.name} ${o.alias}  ${o.kind}  mag ${o.mag.ifEmpty { "?" }}") }
        }
        target?.let { Text("target ${it.alias.ifEmpty { it.name }}  ${it.name}") }

        Text("scale %.1fx".format(scale))
        Slider(scale, { scale = (it * 10).roundToInt() / 10f }, valueRange = 1f..2f, steps = 9)
        if (link.hasWideCamera) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(wide, { wide = it; angle = 0f })
                Text("wide-angle camera")
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(angle == 0f, { angle = 0f }, { Text("0°") })
                FilterChip(angle == 90f, { angle = 90f }, { Text("90°") })
            }
        } else {
            Text("angle %.0f°".format(angle))
            Slider(angle, { angle = it.roundToInt().toFloat() }, valueRange = -90f..90f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(lp, { lp = it })
            Text("LP filter")
        }
        Button({
            val o = target ?: return@Button
            // the scope expects coordinates of date, the catalogue is J2000
            val (ra, dec) = precessFromJ2000(o.raHours, o.decDeg, julianDateNow())
            link.send("iscope_start_view", startViewParams(o.alias.ifEmpty { o.name }, ra, dec, lp, wide, Mosaic(scale.toDouble(), angle.toDouble())))
        }, enabled = link.connected && target != null) { Text("Go to and frame") }
        Text(link.lastReply, style = MaterialTheme.typography.bodySmall)
    }
}
