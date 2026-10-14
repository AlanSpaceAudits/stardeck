package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Firmware the official app 3.3.0 ships with ("8.46"); older firmware gets an update prompt. */
const val APP_FIRMWARE_INT = 2846
const val APP_FIRMWARE_NAME = "8.46"
/** Below this the official app forces the update (the handshake changed there). */
const val FORCE_FIRMWARE_INT = 2626

enum class FirmwareUpdate { NONE, OFFERED, REQUIRED }

data class VersionInfo(
    val firmware: String,
    val firmwareInt: Int?,
    val server: String,
    val serverInt: Int?,
    val iosVer: String,
    val platform: Int?,
    val master: Boolean,
) {
    /** As the official app decides it: only the master client is asked, and no firmware number means no prompt. */
    val update: FirmwareUpdate get() = when {
        !master || firmwareInt == null -> FirmwareUpdate.NONE
        firmwareInt < FORCE_FIRMWARE_INT -> FirmwareUpdate.REQUIRED
        firmwareInt < APP_FIRMWARE_INT -> FirmwareUpdate.OFFERED
        else -> FirmwareUpdate.NONE
    }

    fun lines(): List<String> = listOfNotNull(
        "firmware ${firmware.ifEmpty { "--" }}" + (firmwareInt?.let { " ($it)" } ?: ""),
        if (server.isNotEmpty() || serverInt != null) "server ${server.ifEmpty { "--" }}" + (serverInt?.let { " ($it)" } ?: "") else null,
        if (iosVer.isNotEmpty()) "ios_ver $iosVer" else null,
        platform?.let { "platform $it" },
        if (!master) "this phone is not the master client" else null,
        when (update) {
            FirmwareUpdate.NONE -> "official app: no update prompt"
            FirmwareUpdate.OFFERED -> "official app would offer firmware $APP_FIRMWARE_NAME"
            FirmwareUpdate.REQUIRED -> "official app would require a firmware update"
        },
    )
}

/** `device` and `client` blocks of `get_device_state`. */
fun parseVersionInfo(result: JSONObject?): VersionInfo {
    val d = result?.optJSONObject("device") ?: JSONObject()
    val c = result?.optJSONObject("client")
    fun int(k: String) = if (d.has(k)) d.optInt(k) else null
    return VersionInfo(
        firmware = d.optString("firmware_ver_string"),
        firmwareInt = int("firmware_ver_int"),
        server = d.optString("svr_ver_string"),
        serverInt = int("svr_ver_int"),
        iosVer = d.optString("ios_ver"),
        platform = int("firmware_platform"),
        master = !(c != null && c.has("is_master") && !c.optBoolean("is_master")),
    )
}

/** Firmware and server versions, and whether the official app would ask for a firmware update. */
@Composable
fun VersionPanel(link: Link) {
    var info by remember { mutableStateOf<VersionInfo?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }

    LaunchedEffect(link.connected, refreshes) {
        if (!link.connected) return@LaunchedEffect
        link.request("get_device_state", JSONObject().put("keys", JSONArray().put("device").put("client"))) { r ->
            r?.optJSONObject("result")?.let { info = parseVersionInfo(it) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!link.connected) Text("not connected")
        Text(info?.lines()?.joinToString("\n") ?: "reading…", fontFamily = FontFamily.Monospace)
        OutlinedButton({ refreshes++ }, enabled = link.connected) { Text("Refresh") }
        Text("Firmware upload is not supported here; use the official app to update.", style = MaterialTheme.typography.bodySmall)
    }
}
