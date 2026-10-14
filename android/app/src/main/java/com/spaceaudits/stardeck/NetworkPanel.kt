package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The official app waits this long for the scope to join a network. */
const val STATION_TIMEOUT_SEC = 25

/** The scope's connection to a home network (`pi_station_state`, or `station` in `get_device_state`). */
data class StationState(
    val enabled: Boolean,
    val ssid: String,
    val ip: String,
    val gateway: String,
    val freqMhz: Int?,
    val signalDbm: Int?,
    val keyMgmt: String,
) {
    /** The official app counts the station as joined when it is on and has a gateway. */
    val connected get() = enabled && gateway.isNotEmpty()

    fun line(): String = when {
        connected -> "home Wi-Fi $ssid  $ip" + (freqMhz?.let { "  %.1f GHz".format(it / 1000.0) } ?: "") + (signalDbm?.let { "  $it dBm" } ?: "")
        enabled -> "home Wi-Fi on, not connected" + if (ssid.isNotEmpty()) " ($ssid)" else ""
        else -> "home Wi-Fi off"
    }
}

fun parseStation(o: JSONObject?): StationState? {
    if (o == null) return null
    fun int(k: String) = if (o.has(k)) o.optInt(k) else null
    return StationState(
        enabled = o.optBoolean("server"),
        ssid = o.optString("ssid"),
        ip = o.optString("ip"),
        gateway = o.optString("gateway"),
        freqMhz = int("freq"),
        signalDbm = int("sig_lev"),
        keyMgmt = o.optString("key_mgmt"),
    )
}

data class WifiNetwork(val ssid: String, val freqMhz: Int?, val signalDbm: Int?, val flags: String) {
    /** The official app asks for a password unless the flags are exactly "[ESS]". */
    val open get() = flags == "[ESS]"
    val band get() = freqMhz?.let { if (it >= 4900) "5 GHz" else "2.4 GHz" } ?: ""
}

/** `pi_station_scan` result: one entry per SSID, strongest first. */
fun parseScan(result: Any?): List<WifiNetwork> {
    val a = result as? JSONArray ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .map { WifiNetwork(it.optString("ssid"), if (it.has("freq")) it.optInt("freq") else null, if (it.has("sig_lev")) it.optInt("sig_lev") else null, it.optString("flags")) }
        .filter { it.ssid.isNotBlank() }
        .groupBy { it.ssid }.values.map { same -> same.maxBy { it.signalDbm ?: Int.MIN_VALUE } }
        .sortedByDescending { it.signalDbm ?: Int.MIN_VALUE }
}

/** `pi_station_list` result: saved SSIDs, given as strings or as objects with `ssid`. */
fun parseSavedNetworks(result: Any?): List<String> {
    val a = result as? JSONArray ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        when (val v = a.opt(i)) {
            is String -> v
            is JSONObject -> v.optString("ssid")
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }
}

/** `pi_station_set` params as the official app sends them; psk is left out for an open network. */
fun stationSetParams(ssid: String, psk: String?, hidden: Boolean = false): JSONObject =
    JSONObject().put("ssid", ssid).put("hidden", hidden).put("timeout_sec", STATION_TIMEOUT_SEC)
        .apply { if (!psk.isNullOrEmpty()) put("psk", psk) }

/** Two-letter Wi-Fi country code, or "" for the default region. */
fun normalizeCountry(code: String): String? = code.trim().uppercase().takeIf { it.isEmpty() || Regex("[A-Z]{2}").matches(it) }

fun replyText(method: String, r: JSONObject?): String = when {
    r == null -> "$method: no reply"
    r.optInt("code") != 0 -> "$method: ${r.optString("error").ifEmpty { "code ${r.optInt("code")}" }}"
    else -> "$method: ok"
}

/** Home Wi-Fi (station) state, scan, join, forget, station on/off, the scope's own hotspot and the Wi-Fi country. */
@Composable
fun NetworkPanel(link: Link) {
    var station by remember { mutableStateOf<StationState?>(null) }
    var apSsid by remember { mutableStateOf("") }
    var ap5g by remember { mutableStateOf<Boolean?>(null) }
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var saved by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var refreshes by remember { mutableIntStateOf(0) }
    var join by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // SSID and hidden flag, waiting for a password
    var password by remember { mutableStateOf("") }
    var manualSsid by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf<String?>(null) }

    fun readSaved() = link.request("pi_station_list") { r -> saved = parseSavedNetworks(r?.opt("result")) }

    fun scan() {
        scanning = true
        link.request("pi_station_scan") { r ->
            scanning = false
            if (r == null || r.optInt("code") != 0) reply = replyText("pi_station_scan", r) else networks = parseScan(r.opt("result"))
        }
        readSaved()
    }

    fun call(method: String, params: Any? = null) {
        link.request(method, params) { r -> reply = replyText(method, r); refreshes++ }
    }

    LaunchedEffect(link.connected) {
        if (!link.connected) return@LaunchedEffect
        link.request("get_device_state", JSONObject().put("keys", JSONArray().put("ap"))) { r ->
            r?.optJSONObject("result")?.optJSONObject("ap")?.let { a ->
                apSsid = a.optString("ssid")
                ap5g = if (a.has("is_5g")) a.optBoolean("is_5g") else null
            }
        }
        country = link.setting?.optString("wifi_country").orEmpty()
        readSaved()
    }

    LaunchedEffect(link.connected, refreshes) {
        if (!link.connected) return@LaunchedEffect
        while (true) {
            link.request("pi_station_state") { r -> r?.optJSONObject("result")?.let { parseStation(it) }?.let { station = it } }
            delay(5000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!link.connected) Text("not connected")
        Text(
            listOfNotNull(
                station?.line() ?: "home Wi-Fi --",
                if (apSsid.isNotEmpty()) "hotspot $apSsid" + when (ap5g) { true -> "  5 GHz"; false -> "  2.4 GHz"; null -> "" } else null,
            ).joinToString("\n"),
            fontFamily = FontFamily.Monospace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scan() }, enabled = link.connected && !scanning) { Text(if (scanning) "Scanning…" else "Scan") }
            OutlinedButton({ call("pi_station_open") }, enabled = link.connected) { Text("Station on") }
            OutlinedButton({ call("pi_station_close") }, enabled = link.connected) { Text("Station off") }
        }

        for (n in networks) {
            val isSaved = n.ssid in saved
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${n.ssid}  ${n.band}  ${n.signalDbm?.let { "$it dBm" } ?: ""}${if (n.open) "  open" else ""}${if (isSaved) "  saved" else ""}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton({
                    when {
                        isSaved -> call("pi_station_select", JSONObject().put("ssid", n.ssid))
                        n.open -> call("pi_station_set", stationSetParams(n.ssid, null))
                        else -> { password = ""; join = n.ssid to false }
                    }
                }, enabled = link.connected) { Text("Join") }
                if (isSaved) TextButton({ forget = n.ssid }, enabled = link.connected) { Text("Forget") }
            }
        }
        val savedNotSeen = saved.filter { s -> networks.none { it.ssid == s } }
        for (s in savedNotSeen) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$s  saved, not in range", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ forget = s }, enabled = link.connected) { Text("Forget") }
            }
        }

        Text("Other network", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(manualSsid, { manualSsid = it }, Modifier.fillMaxWidth(), label = { Text("SSID (hidden network)") }, singleLine = true)
        Button({ password = ""; join = manualSsid.trim() to true }, enabled = link.connected && manualSsid.isNotBlank()) { Text("Join") }

        Text("Wi-Fi country", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(country, { country = it.take(2) }, Modifier.weight(1f), label = { Text("code, e.g. US; empty = default") }, singleLine = true)
            val code = normalizeCountry(country)
            Button({ if (code != null) call("set_wifi_country", JSONObject().put("country", code)) }, enabled = link.connected && code != null) { Text("Set") }
        }
        if (reply.isNotEmpty()) Text(reply, style = MaterialTheme.typography.bodySmall)
    }

    // the official app sends hidden = true for a network typed in by hand
    join?.let { (ssid, hidden) ->
        AlertDialog(
            onDismissRequest = { join = null },
            title = { Text("Join $ssid") },
            text = {
                OutlinedTextField(password, { password = it }, label = { Text("password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            },
            confirmButton = {
                TextButton({
                    reply = "joining $ssid…"
                    link.request("pi_station_set", stationSetParams(ssid, password, hidden)) { r -> reply = replyText("pi_station_set", r); refreshes++; readSaved() }
                    join = null
                }) { Text("Join") }
            },
            dismissButton = { TextButton({ join = null }) { Text("Cancel") } },
        )
    }

    forget?.let { ssid ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Forget $ssid?") },
            text = { Text("The telescope removes the saved password. If it is on this network now, it drops off it.") },
            confirmButton = {
                TextButton({
                    link.request("pi_station_remove", JSONObject().put("ssid", ssid)) { r -> reply = replyText("pi_station_remove", r); refreshes++; readSaved() }
                    forget = null
                }) { Text("Forget") }
            },
            dismissButton = { TextButton({ forget = null }) { Text("Cancel") } },
        )
    }
}
