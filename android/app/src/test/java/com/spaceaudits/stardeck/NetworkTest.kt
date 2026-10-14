package com.spaceaudits.stardeck

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    @Test
    fun parsesStationState() {
        val s = parseStation(JSONObject("""{"server":true,"freq":5745,"ip":"192.168.0.79","ssid":"homenet","gateway":"192.168.0.1","sig_lev":-45,"key_mgmt":"WPA2-PSK"}"""))!!
        assertTrue(s.connected)
        assertEquals("homenet", s.ssid)
        assertEquals(5745, s.freqMhz)
        assertEquals(-45, s.signalDbm)
        assertTrue(s.line(), s.line().contains("homenet  192.168.0.79  5.7 GHz  -45 dBm"))
    }

    @Test
    fun stationWithoutGatewayIsNotConnected() {
        val s = parseStation(JSONObject("""{"server":true,"ssid":"homenet"}"""))!!
        assertFalse(s.connected)
        assertEquals("home Wi-Fi off", parseStation(JSONObject("{}"))!!.line())
        assertNull(parseStation(null))
    }

    @Test
    fun scanKeepsStrongestPerSsidAndSorts() {
        val r = JSONArray("""[
            {"ssid":"homenet","freq":2437,"sig_lev":-70,"flags":"[WPA2-PSK-CCMP][ESS]"},
            {"ssid":"homenet","freq":5745,"sig_lev":-48,"flags":"[WPA2-PSK-CCMP][ESS]"},
            {"ssid":"cafe","freq":2412,"sig_lev":-60,"flags":"[ESS]"},
            {"ssid":"","freq":2412,"sig_lev":-30,"flags":"[ESS]"}
        ]""")
        val n = parseScan(r)
        assertEquals(listOf("homenet", "cafe"), n.map { it.ssid })
        assertEquals(5745, n[0].freqMhz)
        assertEquals("5 GHz", n[0].band)
        assertFalse(n[0].open)
        assertTrue(n[1].open)
        assertEquals(emptyList<WifiNetwork>(), parseScan(JSONObject()))
    }

    @Test
    fun savedListAcceptsStringsAndObjects() {
        assertEquals(listOf("a", "b"), parseSavedNetworks(JSONArray("""["a",{"ssid":"b"},{"x":1},""]""")))
        assertEquals(emptyList<String>(), parseSavedNetworks(null))
    }

    @Test
    fun stationSetParamsMatchOfficialApp() {
        val p = stationSetParams("homenet", "secret")
        assertEquals("homenet", p.getString("ssid"))
        assertEquals("secret", p.getString("psk"))
        assertFalse(p.getBoolean("hidden"))
        assertEquals(25, p.getInt("timeout_sec"))
        assertFalse(stationSetParams("cafe", null).has("psk"))
        assertFalse(stationSetParams("cafe", "").has("psk"))
        assertTrue(stationSetParams("x", "y", hidden = true).getBoolean("hidden"))
    }

    @Test
    fun countryCodes() {
        assertEquals("US", normalizeCountry(" us "))
        assertEquals("", normalizeCountry(""))
        assertNull(normalizeCountry("USA"))
        assertNull(normalizeCountry("1A"))
    }
}
