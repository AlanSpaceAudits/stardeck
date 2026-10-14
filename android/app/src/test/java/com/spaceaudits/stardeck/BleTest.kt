package com.spaceaudits.stardeck

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun ad(type: Int, data: ByteArray) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data

    @Test
    fun parsesAdvertisement() {
        // 128-bit UUIDs are sent least significant byte first
        val uuidLe = byteArrayOf(
            0x9f.toByte(), 0x4f, 0x17, 0xd9.toByte(), 0xc2.toByte(), 0xf5.toByte(), 0x3f, 0xa1.toByte(),
            0xf1.toByte(), 0x49, 0xcf.toByte(), 0x6e, 0x01, 0x17, 0x0e, 0x85.toByte(),
        )
        val bytes = ad(0x01, byteArrayOf(0x06)) + ad(0x07, uuidLe) + ad(0x09, "S50_abcd1234".toByteArray()) +
            ad(0xFF, "S50".toByteArray()) + ad(0x03, byteArrayOf(0x0F, 0x18)) + ByteArray(6)
        val a = parseAdvertisement(bytes)
        assertEquals("S50_abcd1234", a.name)
        assertEquals("S50", a.manufacturer)
        assertEquals(listOf(SeestarBle.SERVICE.toString(), "0000180f-0000-1000-8000-00805f9b34fb"), a.uuids.map { it.toString() })
    }

    @Test
    fun truncatedAdvertisementStops() {
        val a = parseAdvertisement(byteArrayOf(0x05, 0x09, 'a'.code.toByte()))
        assertNull(a.name)
        assertTrue(a.uuids.isEmpty())
    }

    @Test
    fun modelNames() {
        assertEquals("Seestar S50", bleModelName("S50"))
        assertEquals("Seestar S30 Pro", bleModelName("S30P1"))
        assertEquals("Seestar S50 Pro", bleModelName("S50P"))
        assertNull(bleModelName("X1"))
        assertNull(bleModelName(null))
    }

    @Test
    fun buildsRequests() {
        val c = JSONObject(bleConnectMessage(id, "US"))
        assertEquals(id, c.getString("ble_sn"))
        assertEquals("ble_connect", c.getString("ble_method"))
        assertFalse(c.getBoolean("nfc_trig"))
        assertEquals("US", c.getString("wifi_country"))

        val v = JSONObject(bleIsVerifiedMessage(id))
        assertEquals("send_to_air_reply", v.getString("ble_method"))
        assertEquals("1", v.getJSONObject("ble_data").getString("id"))
        assertEquals("pi_is_verified", v.getJSONObject("ble_data").getString("method"))

        assertEquals("5G", JSONObject(bleSetBandMessage(id, true)).getString("value"))
        assertEquals("2.4G", JSONObject(bleSetBandMessage(id, false)).getString("value"))
        assertEquals("set_wifi_frequency", JSONObject(bleSetBandMessage(id, false)).getString("ble_method"))

        val country = JSONObject(bleSetCountryMessage(id, "DE"))
        assertEquals("set_wifi_country", country.getString("ble_method"))
        assertEquals("DE", country.getString("value"))

        assertEquals("get_log_size", JSONObject(bleLogSizeMessage(id)).getString("ble_method"))
        assertEquals("start_log_download", JSONObject(bleStartLogMessage(id)).getString("ble_method"))
    }

    @Test
    fun parsesConnectReply() {
        val r = parseBleReply("""{"ble_method":"ble_connect","code":0,"result":{"ssid":"S50_abcd1234","pwd":"12345678","sn":"abcd1234","model":"S50","submodel":"","cpuid":"c0ffee","bssid":"aa:bb:cc:dd:ee:ff","freq":"5G","fw_version":2846,"is_bridge":false,"serc":"x"}}""")!!
        assertEquals("ble_connect", r.method)
        assertEquals(SeestarBle.CODE_OK, r.code)
        val w = parseBleWifi(r.result as JSONObject)
        assertEquals("S50_abcd1234", w.ssid)
        assertEquals("12345678", w.password)
        assertEquals("abcd1234", w.sn)
        assertEquals("5G", w.band)
        assertEquals(2846, w.firmware)
        assertEquals(false, w.bridge)
    }

    @Test
    fun parsesMissingWifiFields() {
        val w = parseBleWifi(JSONObject("""{"ssid":"S30_1"}"""))
        assertEquals("", w.password)
        assertNull(w.firmware)
        assertNull(w.bridge)
    }

    @Test
    fun parsesResetAndErrors() {
        assertEquals(SeestarBle.CODE_PRESS_RESET, parseBleReply("""{"ble_method":"ble_connect","code":1003}""")!!.code)
        val e = parseBleReply("""{"ble_method":"send_to_air_reply","code":1011,"error":"timeout"}""")!!
        assertEquals(SeestarBle.CODE_AIR_ERROR, e.code)
        assertEquals("timeout", e.error)
        assertNull(parseBleReply("not json"))
        assertNull(parseBleReply("""{"code":0}"""))
    }

    @Test
    fun parsesVerifiedAndBand() {
        val v = parseBleReply("""{"ble_method":"send_to_air_reply","code":0,"result":{"method":"pi_is_verified","result":true}}""")!!
        assertEquals(true, parseVerified(v.result))
        assertNull(parseVerified(JSONObject("""{"method":"pi_encrypt","result":0}""")))

        assertEquals("5G" to true, parseBandResult(JSONObject("""{"freq":"5G","is_bridge":true}""")))
        assertEquals("2.4G" to null, parseBandResult("""{"freq":"2.4G"}"""))
        assertNull(parseBandResult("oops"))
    }

    @Test
    fun permissionsBySdk() {
        assertArrayEquals(arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), blePermissions(31))
        assertArrayEquals(arrayOf("android.permission.ACCESS_FINE_LOCATION"), blePermissions(30))
    }
}
