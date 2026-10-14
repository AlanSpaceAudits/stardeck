package com.spaceaudits.stardeck

import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

const val BLE_SCAN_SEC = 20

/** Finds Seestars over Bluetooth, reads the hotspot credentials with `ble_connect`, and switches the hotspot band. */
@Composable
fun BlePanel(onHotspot: (ssid: String, password: String) -> Unit) {
    val context = LocalContext.current
    val perms = remember { blePermissions(Build.VERSION.SDK_INT) }
    fun granted() = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPerms by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPerms = granted() }
    val clientId = remember { bleClientId(context.getSharedPreferences("stardeck", android.content.Context.MODE_PRIVATE)) }

    var scopes by remember { mutableStateOf<List<BleScope>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<BleScope?>(null) }
    var client by remember { mutableStateOf<BleClient?>(null) }
    var wifi by remember { mutableStateOf<BleWifi?>(null) }
    var verified by remember { mutableStateOf<Boolean?>(null) }
    var resetPrompt by remember { mutableStateOf(false) }

    val scanner = remember { BleScanner(context, { scopes = it }, { status = "scan failed (error $it)" }) }

    fun bluetoothOn() = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun disconnect() {
        client?.close()
        client = null
    }

    fun connect(s: BleScope) {
        scanning = false
        disconnect()
        chosen = s
        wifi = null
        verified = null
        resetPrompt = false
        val c = BleClient(context, s.device, clientId) { e ->
            when (e) {
                is BleEvent.Status -> status = e.text
                is BleEvent.Credentials -> {
                    resetPrompt = false
                    wifi = e.wifi
                    status = "hotspot credentials received"
                    client?.isVerified()
                    if (e.wifi.ssid.isNotEmpty()) onHotspot(e.wifi.ssid, e.wifi.password)
                }
                BleEvent.PressReset -> { resetPrompt = true; status = "press RESET on the base of the scope within 60 s" }
                BleEvent.ResetTimeout -> { resetPrompt = false; status = "RESET was not pressed in time" }
                is BleEvent.Verified -> verified = e.verified
                is BleEvent.Band -> { wifi = wifi?.copy(band = e.band, bridge = e.bridge ?: wifi?.bridge); status = "hotspot band ${e.band}" }
                BleEvent.CountrySet -> status = "Wi-Fi country set"
                is BleEvent.LogProgress, is BleEvent.Log -> {}
                is BleEvent.Error -> status = e.text
                BleEvent.Closed -> { client = null; resetPrompt = false }
            }
        }
        client = c
        c.connect()
    }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect scanner.stop()
        if (!scanner.start()) { scanning = false; status = "Bluetooth is off"; return@LaunchedEffect }
        status = "scanning…"
        delay(BLE_SCAN_SEC * 1000L)
        scanning = false
        status = if (scopes.isEmpty()) "no Seestar found" else ""
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.stop()
            client?.close()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!hasPerms) {
            Text(if (perms.size == 1) "Bluetooth scan needs location permission (and location turned on)." else "Bluetooth scan and connect permissions are needed.")
            Button({ launcher.launch(perms) }) { Text("Allow") }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (bluetoothOn()) scanning = true else status = "Bluetooth is off" }, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan") }
            if (scanning) OutlinedButton({ scanning = false }) { Text("Stop") }
            if (client != null) OutlinedButton({ disconnect() }) { Text("Disconnect") }
        }

        for (s in scopes) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${s.name}  ${bleModelName(s.model) ?: s.model.orEmpty()}  ${s.rssi} dBm\n${s.address}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton({ connect(s) }, enabled = client == null || chosen != s) { Text("Connect") }
            }
        }

        wifi?.let { w ->
            Text(
                listOfNotNull(
                    "hotspot ${w.ssid}",
                    "password ${w.password}",
                    "band ${w.band.ifEmpty { "--" }}" + (w.bridge?.let { if (it) "  bridge" else "" } ?: ""),
                    "sn ${w.sn}  ${w.model} ${w.submodel}".trimEnd(),
                    w.firmware?.let { "firmware $it" },
                    verified?.let { if (it) "activated" else "not activated" },
                ).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onHotspot(w.ssid, w.password) }, enabled = w.ssid.isNotEmpty()) { Text("Join hotspot") }
                OutlinedButton({ client?.setBand(false) }, enabled = client != null && w.band != "2.4G") { Text("2.4 GHz") }
                OutlinedButton({ client?.setBand(true) }, enabled = client != null && w.band != "5G") { Text("5 GHz") }
            }
        }
        if (resetPrompt) Text("Press the RESET button on the base of the scope to confirm.", style = MaterialTheme.typography.titleSmall)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
