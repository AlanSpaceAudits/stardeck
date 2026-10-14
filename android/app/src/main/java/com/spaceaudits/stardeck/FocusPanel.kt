package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

val FOCUS_STEPS = listOf(-100, -20, -5, 5, 20, 100)

/** Absolute focuser position for a relative move, kept inside the focuser's range. */
fun focusTarget(step: Int, delta: Int, max: Int): Int = (step + delta).coerceIn(0, max)

/** Focuser position, step buttons, autofocus and the factory focus position. */
@Composable
fun FocusPanel(link: Link) {
    var step by remember { mutableStateOf<Int?>(null) }
    var max by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf("") }
    var factory by remember { mutableStateOf<Int?>(null) }
    var reply by remember { mutableStateOf("") }

    // factory_focal_pos sits in the slower `setting` block: read once, the focuser itself every second
    LaunchedEffect(link.connected) {
        if (!link.connected) return@LaunchedEffect
        link.request("get_device_state", JSONObject().put("keys", JSONArray().put("setting"))) { r ->
            r?.optJSONObject("result")?.optJSONObject("setting")?.let { s -> if (s.has("factory_focal_pos")) factory = s.optInt("factory_focal_pos") }
        }
        while (true) {
            link.request("get_device_state", JSONObject().put("keys", JSONArray().put("focuser"))) { r ->
                r?.optJSONObject("result")?.optJSONObject("focuser")?.let { f ->
                    if (f.has("step")) step = f.optInt("step")
                    if (f.has("max_step")) max = f.optInt("max_step")
                    state = f.optString("state")
                }
            }
            delay(1000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "focuser ${step ?: "--"} / ${max ?: "--"}  $state" + (factory?.let { "\nfactory position $it" } ?: ""),
            fontFamily = FontFamily.Monospace,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in FOCUS_STEPS) {
                val s = step
                val m = max
                OutlinedButton(
                    {
                        if (s != null && m != null) {
                            val target = focusTarget(s, d, m)
                            step = target // so quick taps add up before the next poll
                            link.send("move_focuser", JSONObject().put("step", target).put("ret_step", false))
                        }
                    },
                    Modifier.weight(1f),
                    enabled = link.connected && s != null && m != null,
                    contentPadding = PaddingValues(0.dp),
                ) { Text(if (d > 0) "+$d" else "$d", fontSize = 12.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ link.send("start_auto_focuse") }, enabled = link.connected) { Text("Autofocus") }
            Button({ link.send("stop_auto_focuse") }, enabled = link.connected) { Text("Stop") }
        }
        Button({
            // wide_cam false = the telescope camera, as the official app sends for the main view
            link.request("reset_factory_focal_pos", JSONObject().put("wide_cam", false)) { r ->
                reply = when {
                    r == null -> "reset_factory_focal_pos: no reply"
                    r.optInt("code") != 0 -> "reset_factory_focal_pos: ${r.optString("error").ifEmpty { "code ${r.optInt("code")}" }}"
                    else -> {
                        r.optJSONObject("result")?.let { if (it.has("focal_pos")) step = it.optInt("focal_pos") }
                        "focus returned to the factory position"
                    }
                }
            }
        }, enabled = link.connected) { Text("Return to factory focus") }
        if (reply.isNotEmpty()) Text(reply, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall)
    }
}
