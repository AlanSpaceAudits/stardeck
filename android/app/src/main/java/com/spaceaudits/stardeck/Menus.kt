package com.spaceaudits.stardeck

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.cosinekitty.astronomy.Body
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.sin

/** Level display: tilt at the outer ring, and the tilt shown as good. */
const val LEVEL_FULL_SCALE_DEG = 5.0
const val LEVEL_OK_DEG = 0.5

private fun altText(alt: Double?) = alt?.let { "  alt %.0f°".format(it) } ?: ""

/** Star view, or a goto to a Messier object. Objects above 15° are listed, highest first. */
/** Options set on the scope before a star goto, so the first frames already use them. */
object GotoOptions {
    var lpFilter by mutableStateOf(false)
    /** stack exposure in ms; null leaves the scope's setting */
    var exposureMs by mutableStateOf<Int?>(null)
}

/** Sends the goto options, then the goto. RA hours and Dec degrees of date. */
fun starGoto(link: Link, name: String, raHours: Double, decDeg: Double) {
    GotoOptions.exposureMs?.let { link.send("set_setting", JSONObject().put("exp_ms", JSONObject().put("stack_l", it))) }
    link.send("iscope_start_view", JSONObject().put("mode", "star").put("selected_cam", "View")
        .put("target_name", name).put("target_ra_dec", JSONArray().put(raHours).put(decDeg)).put("lp_filter", GotoOptions.lpFilter))
}

/** Parses "5.5", "5 30 0" or "5:30:00" (hours or degrees, minutes, seconds) to a decimal number. */
fun parseSexagesimal(text: String): Double? {
    val t = text.trim().replace(Regex("[hdms°′″'\":]"), " ").trim()
    val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { it.toDoubleOrNull() ?: return null }
    val sign = if (parts[0].startsWith("-")) -1.0 else 1.0
    return sign * (kotlin.math.abs(nums[0]) + nums.getOrElse(1) { 0.0 } / 60 + nums.getOrElse(2) { 0.0 } / 3600)
}

@Composable
fun StarMenu(link: Link, catalog: Catalog, site: Pair<Double, Double>?, utcMillis: () -> Long, onCustom: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box {
        OverlayButton("Star ▾") { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Star view at the current position") }, {
                link.send("iscope_start_view", JSONObject().put("mode", "star"))
                open = false
            })
            DropdownMenuItem({ Text("Custom target (name, RA, Dec)…") }, { open = false; onCustom() })
            HorizontalDivider()
            // set before the goto, as owners asked for: filter and exposure are right from the first frame
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Checkbox(GotoOptions.lpFilter, { GotoOptions.lpFilter = it }); Text("LP filter")
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("exposure", fontSize = 13.sp)
                for ((label, ms) in listOf("keep" to null, "10 s" to 10000, "20 s" to 20000, "30 s" to 30000)) {
                    Text(label, fontSize = 13.sp, modifier = Modifier.background(
                        if (GotoOptions.exposureMs == ms) Color(0x66FFFFFF) else Color(0x22FFFFFF), RoundedCornerShape(10.dp),
                    ).clickable { GotoOptions.exposureMs = ms }.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            HorizontalDivider()
            val now = utcMillis()
            val jd = now / 86400000.0 + 2440587.5
            val rows = remember(open) {
                if (!open) emptyList() else catalog.messier().map { o ->
                    val (ra, dec) = precessFromJ2000(o.raHours, o.decDeg, jd)
                    Triple(o, ra to dec, site?.let { Sky.altitude(ra, dec, now, it.first, it.second) })
                }.filter { it.third == null || it.third!! > 15.0 }.sortedByDescending { it.third ?: 0.0 }
            }
            for ((o, raDec, alt) in rows) {
                val names = o.other.split('|')
                DropdownMenuItem({ Text("${names[0]}  ${names.getOrElse(1) { o.name }}  ${o.kind}  mag ${o.mag.ifEmpty { "?" }}${altText(alt)}") }, {
                    starGoto(link, names[0], raDec.first, raDec.second)
                    open = false
                })
            }
        }
    }
}

/** Solar view, or a goto to the Sun, the Moon or a planet. Targets near the Sun need the filter tick. */
@Composable
fun SolarMenu(link: Link, site: Pair<Double, Double>?, utcMillis: () -> Long) {
    var open by remember { mutableStateOf(false) }
    var filterOn by remember { mutableStateOf(false) }
    Box {
        OverlayButton("Solar ▾") { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Solar view at the current position") }, {
                link.send("iscope_start_view", JSONObject().put("mode", "solar_sys"))
                open = false
            })
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(filterOn, { filterOn = it })
                Text("solar filter is fitted")
            }
            if (open) {
                val now = utcMillis()
                fun alt(body: Body): Double? = site?.let {
                    val (ra, dec) = Sky.raDec(body, now, it.first, it.second)
                    Sky.altitude(ra, dec, now, it.first, it.second)
                }
                DropdownMenuItem({ Text("Sun${altText(alt(Body.Sun))}") }, {
                    link.send("iscope_start_view", JSONObject().put("mode", "solar_sys").put("target_type", "sun").put("target_name", "Sun"))
                    open = false
                }, enabled = filterOn)
                // A target close to the Sun puts the Sun in or near the field: same rule as the Sun itself.
                val moonNear = Sky.degreesFromSun(Body.Moon, now) < Sky.SUN_GUARD_DEG
                DropdownMenuItem({ Text("Moon${altText(alt(Body.Moon))}${if (moonNear) "  (near the Sun)" else ""}") }, {
                    link.send("iscope_start_view", JSONObject().put("mode", "solar_sys").put("target_type", "moon").put("target_name", "Moon"))
                    open = false
                }, enabled = filterOn || !moonNear)
                HorizontalDivider()
                for ((name, body) in Sky.PLANETS) {
                    val near = Sky.degreesFromSun(body, now) < Sky.SUN_GUARD_DEG
                    DropdownMenuItem({ Text("$name${altText(alt(body))}${if (near) "  (near the Sun)" else ""}") }, {
                        val (ra, dec) = Sky.raDec(body, now)
                        link.send("iscope_start_view", JSONObject().put("mode", "solar_sys").put("target_type", "planet").put("target_name", name)
                            .put("target_ra_dec", JSONArray().put(ra).put(dec)))
                        open = false
                    }, enabled = filterOn || !near)
                }
            }
        }
    }
}

/** Bubble level from the scope's tilt sensor, with the compass heading and the calibration commands. */
@Composable
fun LevelPanel(link: Link) {
    val balance = link.sensors?.optJSONObject("balance_sensor")?.optJSONObject("data")
    val compass = link.sensors?.optJSONObject("compass_sensor")?.optJSONObject("data")
    fun num(o: JSONObject?, k: String) = o?.optDouble(k)?.takeIf { !it.isNaN() }
    val x = num(balance, "x") ?: 0.0
    val y = num(balance, "y") ?: 0.0
    val tilt = num(balance, "angle")
    val good = tilt != null && tilt <= LEVEL_OK_DEG
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(200.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2 - 4
            drawCircle(Color.Gray, radius, c, style = Stroke(2f))
            drawCircle(Color(0xFF5AC86E), radius * (LEVEL_OK_DEG / LEVEL_FULL_SCALE_DEG).toFloat(), c, style = Stroke(2f))
            drawLine(Color.Gray, c - Offset(radius, 0f), c + Offset(radius, 0f), 2f)
            drawLine(Color.Gray, c - Offset(0f, radius), c + Offset(0f, radius), 2f)
            // x and y are gravity components (sine of tilt); the outer ring is LEVEL_FULL_SCALE_DEG
            val scale = radius / sin(Math.toRadians(LEVEL_FULL_SCALE_DEG))
            var dx = (x * scale).toFloat()
            var dy = (-y * scale).toFloat()
            val len = hypot(dx, dy)
            if (len > radius) {
                dx *= radius / len
                dy *= radius / len
            }
            drawCircle(if (good) Color(0xFF5AC86E) else Color(0xFFFFAA3C), 14f, c + Offset(dx, dy))
        }
        Text(
            "tilt ${tilt?.let { "%.2f°".format(it) } ?: "--"}\nx %+.2f°  y %+.2f°".format(
                Math.toDegrees(asin(x.coerceIn(-1.0, 1.0))), Math.toDegrees(asin(y.coerceIn(-1.0, 1.0)))),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "compass ${num(compass, "direction")?.let { "%.1f°".format(it) } ?: "--"}  (${if (compass?.optInt("cali") == 0) "not calibrated" else "calibrated"})",
            fontFamily = FontFamily.Monospace,
        )
        Text("Green ring = $LEVEL_OK_DEG°, outer ring = $LEVEL_FULL_SCALE_DEG°. Adjust the tripod until the dot is in the green ring.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ link.send("start_compass_calibration") }) { Text("Calibrate compass") }
            Button({ link.send("stop_compass_calibration", JSONObject().put("force", false)) }) { Text("Stop") }
        }
        // Sets the sensor zero: only when the telescope is level.
        Button({ link.send("start_gsensor_calibration") }) { Text("Calibrate level sensor (scope must be level)") }
    }
}
