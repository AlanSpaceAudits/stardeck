package com.spaceaudits.stardeck

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** GATT layout of the Seestar BLE service (`BleUtilsV2`, `BleUtils`). */
object SeestarBle {
    val SERVICE: UUID = UUID.fromString("850e1701-6ecf-49f1-a13f-f5c2d9174f9f")
    /** Requests are written here; replies come back as notifications on the same characteristic. */
    val COMMAND: UUID = UUID.fromString("850e1702-6ecf-49f1-a13f-f5c2d9174f9f")
    /** Readable status JSON; defined in the official app but never read by it. */
    val STATUS: UUID = UUID.fromString("850e1703-6ecf-49f1-a13f-f5c2d9174f9f")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MTU = 500
    const val LOG_MTU = 515

    const val CODE_OK = 0
    /** ble_connect: the user must press RESET on the scope within 60 s. */
    const val CODE_PRESS_RESET = 1003
    /** ble_connect: RESET was not pressed in time. */
    const val CODE_RESET_TIMEOUT = 1008
    /** send_to_air_reply: the scope's main process did not answer. */
    const val CODE_AIR_ERROR = 1011
}

/** Runtime permissions for scan and connect: the Android 12 Bluetooth pair, or fine location before that. */
fun blePermissions(sdk: Int): Array<String> =
    if (sdk >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Per-install client id sent as `ble_sn`; the official app uses a random UUID kept for the life of the install. */
fun bleClientId(prefs: SharedPreferences): String =
    prefs.getString("ble_client_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("ble_client_id", it).apply() }

data class BleAdvertisement(val name: String?, val manufacturer: String?, val uuids: List<UUID>)

/** Raw advertisement parse as `BtParseInfo` does it: the whole manufacturer field, company id included, read as text. */
fun parseAdvertisement(bytes: ByteArray): BleAdvertisement {
    var name: String? = null
    var manufacturer: String? = null
    val uuids = mutableListOf<UUID>()
    var i = 0
    while (i + 1 < bytes.size) {
        val len = bytes[i].toInt() and 0xFF
        if (len == 0 || i + 1 + len > bytes.size) break
        val type = bytes[i + 1].toInt() and 0xFF
        val data = bytes.copyOfRange(i + 2, i + 1 + len)
        when (type) {
            0x02, 0x03 -> for (k in 0 until data.size / 2) {
                val v = (data[2 * k].toInt() and 0xFF) or ((data[2 * k + 1].toInt() and 0xFF) shl 8)
                uuids += UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))
            }
            0x06, 0x07 -> for (k in 0 until data.size / 16) {
                val be = data.copyOfRange(16 * k, 16 * k + 16).reversedArray()
                uuids += UUID(be.copyOfRange(0, 8).toLong(), be.copyOfRange(8, 16).toLong())
            }
            0x08, 0x09 -> name = String(data, Charsets.UTF_8).trim()
            0xFF -> manufacturer = String(data, Charsets.UTF_8).trim()
        }
        i += 1 + len
    }
    return BleAdvertisement(name, manufacturer, uuids)
}

private fun ByteArray.toLong(): Long = fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

/** Model from the advertised manufacturer text (`DeviceDiscoveryConnectDialog$connectBle$1$onModel$1`). */
fun bleModelName(code: String?): String? = when (code) {
    "S30" -> "Seestar S30"
    "S50" -> "Seestar S50"
    "S30P", "S30P1" -> "Seestar S30 Pro"
    "S50P" -> "Seestar S50 Pro"
    else -> null
}

private fun bleMessage(clientId: String, method: String, extra: JSONObject.() -> Unit = {}): String =
    JSONObject().put("ble_sn", clientId).put("ble_method", method).apply(extra).toString()

/** First request after notifications are on; the reply carries the hotspot credentials. */
fun bleConnectMessage(clientId: String, country: String, nfc: Boolean = false) =
    bleMessage(clientId, "ble_connect") { put("nfc_trig", nfc); put("wifi_country", country) }

/** JSON-RPC request relayed to the scope's main process. */
fun bleAirMessage(clientId: String, id: Any, method: String) =
    bleMessage(clientId, "send_to_air_reply") { put("ble_data", JSONObject().put("id", id).put("method", method)) }

fun bleIsVerifiedMessage(clientId: String) = bleAirMessage(clientId, "1", "pi_is_verified")

/** Hotspot band: true for 5 GHz. */
fun bleSetBandMessage(clientId: String, fiveG: Boolean) =
    bleMessage(clientId, "set_wifi_frequency") { put("value", if (fiveG) "5G" else "2.4G") }

fun bleSetCountryMessage(clientId: String, country: String) =
    bleMessage(clientId, "set_wifi_country") { put("value", country) }

fun bleLogSizeMessage(clientId: String) = bleMessage(clientId, "get_log_size")
fun bleStartLogMessage(clientId: String) = bleMessage(clientId, "start_log_download")

data class BleReply(val method: String, val code: Int, val error: String, val result: Any?)

fun parseBleReply(text: String): BleReply? = runCatching {
    val o = JSONObject(text)
    BleReply(o.getString("ble_method"), o.optInt("code"), o.optString("error"), o.opt("result"))
}.getOrNull()

/** `ble_connect` result (`BleWifiData`). `band` is "2.4G" or "5G". */
data class BleWifi(
    val ssid: String,
    val password: String,
    val sn: String,
    val model: String,
    val submodel: String,
    val cpuId: String,
    val bssid: String,
    val band: String,
    val firmware: Int?,
    val bridge: Boolean?,
)

fun parseBleWifi(o: JSONObject): BleWifi = BleWifi(
    ssid = o.optString("ssid"),
    password = o.optString("pwd"),
    sn = o.optString("sn"),
    model = o.optString("model"),
    submodel = o.optString("submodel"),
    cpuId = o.optString("cpuid"),
    bssid = o.optString("bssid"),
    band = o.optString("freq"),
    firmware = if (o.has("fw_version")) o.optInt("fw_version") else null,
    bridge = if (o.has("is_bridge")) o.optBoolean("is_bridge") else null,
)

/** `set_wifi_frequency` reply: result is an object, or the same object as a JSON string. */
fun parseBandResult(result: Any?): Pair<String, Boolean?>? {
    val o = when (result) {
        is JSONObject -> result
        is String -> runCatching { JSONObject(result) }.getOrNull()
        else -> null
    } ?: return null
    return o.optString("freq") to (if (o.has("is_bridge")) o.optBoolean("is_bridge") else null)
}

/** `pi_is_verified` reply: result is `{method, result: bool}`. */
fun parseVerified(result: Any?): Boolean? =
    (result as? JSONObject)?.takeIf { it.optString("method") == "pi_is_verified" && it.has("result") }?.optBoolean("result")

data class BleScope(val name: String, val address: String, val rssi: Int, val model: String?, val device: BluetoothDevice)

/** Scans for the Seestar service UUID; nameless results are dropped as the official app does. Callbacks run on the main thread. */
@SuppressLint("MissingPermission")
class BleScanner(context: Context, private val onUpdate: (List<BleScope>) -> Unit, private val onError: (Int) -> Unit) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val found = linkedMapOf<String, BleScope>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val name = record.deviceName?.takeIf { it.isNotEmpty() } ?: return
            val adv = parseAdvertisement(record.bytes ?: ByteArray(0))
            found[result.device.address] = BleScope(name, result.device.address, result.rssi, adv.manufacturer, result.device)
            onUpdate(found.values.sortedByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) = onError(errorCode)
    }

    /** False when Bluetooth is off or the phone has no LE scanner. */
    fun start(): Boolean {
        val s = scanner ?: return false
        found.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SeestarBle.SERVICE)).build()
        s.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        return true
    }

    fun stop() {
        runCatching { scanner?.stopScan(callback) }
    }
}

sealed interface BleEvent {
    data class Status(val text: String) : BleEvent
    data class Credentials(val wifi: BleWifi) : BleEvent
    data object PressReset : BleEvent
    data object ResetTimeout : BleEvent
    data class Verified(val verified: Boolean) : BleEvent
    data class Band(val band: String, val bridge: Boolean?) : BleEvent
    data object CountrySet : BleEvent
    data class LogProgress(val received: Long, val size: Long) : BleEvent
    data class Log(val bytes: ByteArray) : BleEvent
    data class Error(val text: String) : BleEvent
    data object Closed : BleEvent
}

/**
 * One GATT session with a scope, in the order the official app uses: MTU, notifications on the
 * command characteristic, then `ble_connect` after 80 ms. Events are posted to the main thread.
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val clientId: String,
    private val country: String = "",
    private val forLog: Boolean = false,
    private val onEvent: (BleEvent) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var closed = false
    private var logSize = -1L
    private var log: ByteArrayOutputStream? = null

    private fun emit(e: BleEvent) {
        main.post { if (!closed) onEvent(e) }
    }

    fun connect() {
        emit(BleEvent.Status("connecting to ${device.address}"))
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun isVerified() = send(bleIsVerifiedMessage(clientId))
    fun setBand(fiveG: Boolean) = send(bleSetBandMessage(clientId, fiveG))
    fun setCountry(code: String) = send(bleSetCountryMessage(clientId, code))

    /** Reads the scope log; open the client with [forLog] so the session skips `ble_connect`. */
    fun downloadLog() {
        logSize = -1
        log = null
        send(bleLogSizeMessage(clientId))
    }

    fun close() {
        if (closed) return
        closed = true
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        main.post { onEvent(BleEvent.Closed) }
    }

    private fun send(text: String) {
        synchronized(queue) { queue.addLast(text.toByteArray(Charsets.UTF_8)) }
        pump()
    }

    // GATT runs one operation at a time; the next write goes out from onCharacteristicWrite
    private fun pump() {
        val g = gatt ?: return
        val c = command ?: return
        val data = synchronized(queue) {
            if (writing || queue.isEmpty()) return
            writing = true
            queue.removeFirst()
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, data, c.writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) {
            synchronized(queue) { writing = false }
            emit(BleEvent.Error("write failed"))
        }
    }

    private fun handle(data: ByteArray) {
        val buf = log
        if (buf != null) {
            buf.write(data)
            emit(BleEvent.LogProgress(buf.size().toLong(), logSize))
            if (buf.size() >= logSize) {
                log = null
                emit(BleEvent.Log(buf.toByteArray()))
            }
            return
        }
        val text = String(data, Charsets.UTF_8)
        val r = parseBleReply(text) ?: return emit(BleEvent.Error("unreadable reply: $text"))
        when (r.method) {
            "ble_connect" -> when (r.code) {
                SeestarBle.CODE_OK -> (r.result as? JSONObject)?.let { emit(BleEvent.Credentials(parseBleWifi(it))) }
                    ?: emit(BleEvent.Error("ble_connect: no result"))
                SeestarBle.CODE_PRESS_RESET -> emit(BleEvent.PressReset)
                SeestarBle.CODE_RESET_TIMEOUT -> emit(BleEvent.ResetTimeout)
                else -> emit(BleEvent.Error("ble_connect: ${r.error.ifEmpty { "code ${r.code}" }}"))
            }
            "send_to_air_reply" -> when {
                r.code == SeestarBle.CODE_AIR_ERROR -> emit(BleEvent.Error("scope not answering: ${r.error}"))
                r.code != 0 -> emit(BleEvent.Error("send_to_air_reply: ${r.error.ifEmpty { "code ${r.code}" }}"))
                else -> parseVerified(r.result)?.let { emit(BleEvent.Verified(it)) }
            }
            "set_wifi_frequency" -> if (r.code == 0) parseBandResult(r.result)?.let { (band, bridge) -> emit(BleEvent.Band(band, bridge)) }
                else emit(BleEvent.Error("set_wifi_frequency: ${r.result ?: r.error}"))
            "set_wifi_country" -> emit(BleEvent.CountrySet)
            "get_log_size" -> {
                val size = (r.result as? JSONObject)?.optLong("log_size", -1) ?: -1
                if (r.code != 0 || size < 0) return emit(BleEvent.Error("get_log_size: code ${r.code}"))
                logSize = size
                if (size == 0L) return emit(BleEvent.Log(ByteArray(0)))
                send(bleStartLogMessage(clientId))
            }
            // every notification after this one is raw log data
            "start_log_download" -> log = ByteArrayOutputStream()
            else -> emit(BleEvent.Status("unhandled ${r.method}: $text"))
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(BleEvent.Status("connected, setting MTU"))
                g.requestMtu(if (forLog) SeestarBle.LOG_MTU else SeestarBle.MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!closed) emit(BleEvent.Error("disconnected (status $status)"))
                main.post { close() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SeestarBle.SERVICE)?.getCharacteristic(SeestarBle.COMMAND)
                ?: return emit(BleEvent.Error("Seestar service not found"))
            command = c
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(SeestarBle.CCCD) ?: return emit(BleEvent.Error("no notify descriptor"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return emit(BleEvent.Error("notify setup failed ($status)"))
            emit(BleEvent.Status("notifications on"))
            if (!forLog) main.postDelayed({ send(bleConnectMessage(clientId, country)) }, 80)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(queue) { writing = false }
            if (status != BluetoothGatt.GATT_SUCCESS) emit(BleEvent.Error("write status $status"))
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == SeestarBle.COMMAND) handle(value)
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (c.uuid == SeestarBle.COMMAND) handle(c.value ?: return)
        }
    }
}
