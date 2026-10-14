package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/** The official app always sends this heater value. */
const val HEATER_DEFAULT = 90

data class DeviceInfo(
    val model: String,
    val serial: String,
    val firmware: String,
    val battery: Int?,
    val charger: String,
    val chargeOnline: Boolean,
    val cpuTempC: Double?,
    val batteryTempC: Double?,
    val overTemp: Boolean,
    val storageFreeMb: Long?,
    val storageTotalMb: Long?,
    val storageName: String,
    val wifiSsid: String,
    val wifiIp: String,
    val wifiFreqMhz: Int?,
    val wifiSignalDbm: Int?,
    val apSsid: String,
    val heaterOn: Boolean?,
) {
    fun lines(): List<String> = listOfNotNull(
        "$model  serial $serial",
        "firmware $firmware",
        "battery ${battery?.let { "$it%" } ?: "--"}  ${charger.ifEmpty { if (chargeOnline) "charging" else "" }}".trimEnd(),
        "CPU ${cpuTempC?.let { "%.1f °C".format(it) } ?: "--"}  battery ${batteryTempC?.let { "%.0f °C".format(it) } ?: "--"}" + if (overTemp) "  OVER TEMPERATURE" else "",
        if (storageTotalMb != null) "storage $storageName ${formatBytes((storageFreeMb ?: 0) * 1048576)} free of ${formatBytes(storageTotalMb * 1048576)}" else null,
        if (wifiSsid.isNotEmpty()) "Wi-Fi $wifiSsid  $wifiIp" + (wifiFreqMhz?.let { "  %.1f GHz".format(it / 1000.0) } ?: "") + (wifiSignalDbm?.let { "  $it dBm" } ?: "") else null,
        if (apSsid.isNotEmpty()) "hotspot $apSsid" else null,
    )
}

/** The `result` of `get_device_state`. Passwords in it are not read. */
fun parseDeviceInfo(result: JSONObject?): DeviceInfo {
    val r = result ?: JSONObject()
    val device = r.optJSONObject("device") ?: JSONObject()
    val pi = r.optJSONObject("pi_status") ?: JSONObject()
    val storage = r.optJSONObject("storage") ?: JSONObject()
    val station = r.optJSONObject("station") ?: JSONObject()
    val setting = r.optJSONObject("setting")
    val cur = storage.optString("cur_storage")
    val volumes = storage.optJSONArray("storage_volume")
    val volume = volumes?.let { v -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.let { all -> all.firstOrNull { it.optString("name") == cur } ?: all.firstOrNull() } }
    fun num(o: JSONObject, k: String) = o.optDouble(k).takeIf { !it.isNaN() }
    fun int(o: JSONObject, k: String) = if (o.has(k)) o.optInt(k) else null
    fun long(o: JSONObject?, vararg keys: String) = o?.let { v -> keys.firstOrNull { v.has(it) }?.let { v.optLong(it) } }
    return DeviceInfo(
        model = device.optString("product_model").ifEmpty { device.optString("name") },
        serial = device.optString("sn"),
        firmware = device.optString("firmware_ver_string"),
        battery = int(pi, "battery_capacity"),
        charger = pi.optString("charger_status"),
        chargeOnline = pi.optBoolean("charge_online"),
        cpuTempC = num(pi, "temp"),
        batteryTempC = num(pi, "battery_temp"),
        overTemp = pi.optBoolean("is_overtemp") || pi.optBoolean("battery_overtemp"),
        storageFreeMb = long(volume, "free_mb", "freeMB"),
        storageTotalMb = long(volume, "total_mb", "totalMB"),
        storageName = volume?.optString("name") ?: "",
        wifiSsid = station.optString("ssid"),
        wifiIp = station.optString("ip"),
        wifiFreqMhz = int(station, "freq"),
        wifiSignalDbm = int(station, "sig_lev"),
        apSsid = r.optJSONObject("ap")?.optString("ssid") ?: "",
        heaterOn = setting?.takeIf { it.has("heater_enable") }?.optBoolean("heater_enable"),
    )
}

/** Model, serial, firmware, battery, temperatures, storage, Wi-Fi, dew heater, reboot and shut down. */
@Composable
fun DevicePanel(link: Link) {
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    var heaterLevel by remember { mutableFloatStateOf(HEATER_DEFAULT.toFloat()) }
    var heaterOn by remember { mutableStateOf<Boolean?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // pi_reboot or pi_shutdown

    LaunchedEffect(link.connected, refreshes) {
        if (!link.connected) return@LaunchedEffect
        // this subset takes about a second, so it is polled slowly
        val keys = JSONArray().put("device").put("pi_status").put("storage").put("station").put("ap").put("setting")
        while (true) {
            link.request("get_device_state", JSONObject().put("keys", keys)) { r ->
                r?.optJSONObject("result")?.let { parseDeviceInfo(it) }?.let { info = it; heaterOn = it.heaterOn }
            }
            delay(15000)
        }
    }

    fun setHeater(on: Boolean) {
        heaterOn = on
        link.send("pi_output_set2", JSONObject().put("heater", JSONObject().put("state", on).put("value", heaterLevel.toInt())))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!link.connected) Text("not connected")
        Text(info?.lines()?.joinToString("\n") ?: "reading…", fontFamily = FontFamily.Monospace)
        OutlinedButton({ refreshes++ }, enabled = link.connected) { Text("Refresh") }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(heaterOn == true, { setHeater(it) }, enabled = link.connected)
            Text("dew heater  ${if (heaterOn == null) "--" else if (heaterOn == true) "on" else "off"}  level ${heaterLevel.toInt()}")
        }
        Slider(heaterLevel, { heaterLevel = it }, valueRange = 0f..100f, onValueChangeFinished = { if (heaterOn == true) setHeater(true) })
        Text("The official app always uses level $HEATER_DEFAULT.", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirm = "pi_reboot" }, enabled = link.connected) { Text("Reboot") }
            Button({ confirm = "pi_shutdown" }, enabled = link.connected) { Text("Shut down") }
        }
    }

    confirm?.let { method ->
        val what = if (method == "pi_reboot") "Reboot" else "Shut down"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("$what the telescope?") },
            text = { Text(if (method == "pi_reboot") "The connection drops until the telescope is back." else "The telescope turns off. Turn it on again with its power button.") },
            confirmButton = { TextButton({ link.send(method); confirm = null }) { Text(what) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}
