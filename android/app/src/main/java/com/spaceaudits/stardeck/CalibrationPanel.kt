package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The official app's default for `dec_pos_index` (its picker offers 1 to 5). */
const val PA_DEC_POS_DEFAULT = 3

/** One stage of `iscope_get_app_state` (DarkLibrary, 3PPA, EqModePA...). */
data class StageProgress(val state: String, val percent: Double?, val code: Int?, val error: String) {
    val working get() = state == "working"
    fun line(): String = state.ifEmpty { "idle" } +
        (percent?.let { "  %.0f%%".format(it) } ?: "") +
        (code?.takeIf { it != 0 }?.let { "  code $it" } ?: "") +
        (if (error.isNotEmpty()) "  $error" else "")
}

/** Finds a stage object by name anywhere in the app state, e.g. View.DarkLibrary or View.Initialise.3PPA. */
fun findStage(o: JSONObject?, name: String): JSONObject? {
    if (o == null) return null
    o.optJSONObject(name)?.let { return it }
    for (k in o.keys()) o.optJSONObject(k)?.let { child -> findStage(child, name)?.let { return it } }
    return null
}

fun parseStage(o: JSONObject?): StageProgress? {
    if (o == null) return null
    return StageProgress(
        state = o.optString("state"),
        percent = o.optDouble("percent").takeIf { !it.isNaN() },
        code = if (o.has("code")) o.optInt("code") else null,
        error = o.optString("error"),
    )
}

/** Polar alignment error as the official app reads it from the EqModePA stage. */
data class PolarError(val xArcsec: Double?, val yArcsec: Double?, val x: Double?, val y: Double?) {
    /** The official app shows "aligned" when |x| and |y| stay at or below 1 for 5 s. */
    val aligned get() = x != null && y != null && kotlin.math.abs(x) <= 1.0 && kotlin.math.abs(y) <= 1.0

    fun line(): String = listOfNotNull(
        if (xArcsec != null || yArcsec != null) "error x ${xArcsec?.let { "%.0f\"".format(it) } ?: "--"}  y ${yArcsec?.let { "%.0f\"".format(it) } ?: "--"}" else null,
        if (x != null && y != null) "offset x %+.2f  y %+.2f".format(x, y) + if (aligned) "  aligned" else "" else null,
    ).joinToString("\n")
}

fun parsePolarError(o: JSONObject?): PolarError? {
    if (o == null) return null
    fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() }
    val e = PolarError(num("x_arcsec"), num("y_arcsec"), num("x"), num("y"))
    return e.takeIf { listOf(it.xArcsec, it.yArcsec, it.x, it.y).any { v -> v != null } }
}

/** `start_polar_align` params: a first run picks the declination position; a refresh sends restart false only. */
fun polarAlignParams(restart: Boolean, decPosIndex: Int): JSONObject =
    JSONObject().put("restart", restart).apply { if (restart) put("dec_pos_index", decPosIndex.coerceIn(1, 5)) }

/** Dark library, 3PPA and EQ-mode polar alignment. Progress comes from polling `iscope_get_app_state`. */
@Composable
fun CalibrationPanel(link: Link) {
    var dark by remember { mutableStateOf<StageProgress?>(null) }
    var ppa by remember { mutableStateOf<StageProgress?>(null) }
    var eqPa by remember { mutableStateOf<StageProgress?>(null) }
    var paError by remember { mutableStateOf<PolarError?>(null) }
    var eqMode by remember { mutableStateOf<Boolean?>(null) }
    var paAltValid by remember { mutableStateOf<Boolean?>(null) }
    var decPos by remember { mutableIntStateOf(PA_DEC_POS_DEFAULT) }
    var reply by remember { mutableStateOf("") }

    fun call(method: String, params: Any? = null) = link.request(method, params) { r -> reply = replyText(method, r) }

    LaunchedEffect(link.connected) {
        if (!link.connected) return@LaunchedEffect
        link.request("get_device_state", JSONObject().put("keys", JSONArray().put("mount"))) { r ->
            r?.optJSONObject("result")?.optJSONObject("mount")?.let { if (it.has("equ_mode")) eqMode = it.optBoolean("equ_mode") }
        }
        while (true) {
            link.request("iscope_get_app_state") { r ->
                val s = r?.optJSONObject("result")
                dark = parseStage(findStage(s, "DarkLibrary"))
                ppa = parseStage(findStage(s, "3PPA"))
                val pa = findStage(s, "EqModePA")
                eqPa = parseStage(pa)
                parsePolarError(pa)?.let { paError = it }
            }
            delay(1500)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!link.connected) Text("not connected")

        Text("Dark library", style = MaterialTheme.typography.titleSmall)
        Text("dark library ${dark?.line() ?: "--"}", fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ call("start_create_dark") }, enabled = link.connected) { Text("Create") }
            OutlinedButton({ call("start_create_dark", JSONObject().put("wide_cam", true)) }, enabled = link.connected) { Text("Wide camera") }
            Button({ call("iscope_stop_view", JSONObject().put("stage", "DarkLibrary")) }, enabled = link.connected) { Text("Stop") }
        }

        Text("3PPA calibration", style = MaterialTheme.typography.titleSmall)
        Text("3PPA ${ppa?.line() ?: "--"}", fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val auto = link.settings["auto_3ppa_calib"]
            Switch(auto == true, { link.send("set_setting", JSONObject().put("auto_3ppa_calib", it)) }, enabled = link.connected && auto != null)
            Text("run 3PPA when star mode starts  ${if (auto == null) "--" else if (auto) "on" else "off"}")
        }
        Button({ call("iscope_stop_view", JSONObject().put("stage", "3PPA")) }, enabled = link.connected) { Text("Stop 3PPA") }

        Text("Polar alignment (EQ mode)", style = MaterialTheme.typography.titleSmall)
        Text(
            listOfNotNull(
                "mount ${when (eqMode) { true -> "equatorial"; false -> "alt-az"; null -> "--" }}",
                paAltValid?.let { "polar alignment check ${if (it) "valid" else "not valid"}" },
                "alignment ${eqPa?.line() ?: "--"}",
                paError?.line()?.takeIf { it.isNotEmpty() },
            ).joinToString("\n"),
            fontFamily = FontFamily.Monospace,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("dec position")
            for (i in 1..5) {
                OutlinedButton({ decPos = i }, contentPadding = PaddingValues(0.dp), enabled = decPos != i) { Text("$i") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ paError = null; call("start_polar_align", polarAlignParams(true, decPos)) }, enabled = link.connected) { Text("Start") }
            OutlinedButton({ call("start_polar_align", polarAlignParams(false, decPos)) }, enabled = link.connected) { Text("Measure again") }
            Button({ call("stop_polar_align") }, enabled = link.connected) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                link.request("check_pa_alt") { r ->
                    reply = replyText("check_pa_alt", r)
                    r?.optJSONObject("result")?.takeIf { it.has("is_valid") }?.let { paAltValid = it.optBoolean("is_valid") }
                }
            }, enabled = link.connected) { Text("Check") }
            OutlinedButton({ paError = null; paAltValid = null; call("clear_polar_align") }, enabled = link.connected) { Text("Clear alignment") }
        }
        if (reply.isNotEmpty()) Text(reply, style = MaterialTheme.typography.bodySmall)
    }
}
