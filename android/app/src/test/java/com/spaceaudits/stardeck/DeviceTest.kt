package com.spaceaudits.stardeck

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {
    // get_device_state result recorded from an S50, firmware 8.46, with passwords and other keys removed, names changed
    private val state = """{
        "device":{"name":"ASI AIR imager","firmware_ver_int":2846,"firmware_ver_string":"8.46","sn":"0a1b2c3d","product_model":"Seestar S50"},
        "setting":{"heater_enable":false,"focal_pos":1555,"factory_focal_pos":1555},
        "focuser":{"state":"idle","max_step":2600,"step":1555},
        "ap":{"ssid":"S50_0a1b2c3d","is_5g":true},
        "station":{"server":true,"freq":5745,"ip":"192.168.0.79","ssid":"homenet","gateway":"192.168.0.1","sig_lev":-45,"key_mgmt":"WPA2-PSK"},
        "storage":{"connected_storage":["emmc"],"storage_volume":[{"name":"emmc","state":"mounted","total_mb":51854,"totalMB":51854,"free_mb":51479,"freeMB":51479}],"cur_storage":"emmc"},
        "pi_status":{"is_overtemp":false,"temp":62.400002,"charger_status":"Charging","battery_capacity":65,"charge_online":true,"battery_overtemp":false,"battery_temp":26}
    }"""

    @Test
    fun parsesDeviceState() {
        val d = parseDeviceInfo(JSONObject(state))
        assertEquals("Seestar S50", d.model)
        assertEquals("0a1b2c3d", d.serial)
        assertEquals("8.46", d.firmware)
        assertEquals(65, d.battery)
        assertEquals("Charging", d.charger)
        assertTrue(d.chargeOnline)
        assertEquals(62.4, d.cpuTempC!!, 0.001)
        assertEquals(26.0, d.batteryTempC!!, 0.001)
        assertFalse(d.overTemp)
        assertEquals(51479L, d.storageFreeMb)
        assertEquals(51854L, d.storageTotalMb)
        assertEquals("homenet", d.wifiSsid)
        assertEquals(5745, d.wifiFreqMhz)
        assertEquals(-45, d.wifiSignalDbm)
        assertEquals("S50_0a1b2c3d", d.apSsid)
        assertEquals(false, d.heaterOn)
        val text = d.lines().joinToString("\n")
        assertTrue(text, text.contains("battery 65%  Charging"))
        assertTrue(text, text.contains("50.3 GB free of 50.6 GB"))
        assertTrue(text, text.contains("5.7 GHz  -45 dBm"))
    }

    @Test
    fun missingBlocksGiveEmptyValues() {
        val d = parseDeviceInfo(JSONObject("{}"))
        assertEquals("", d.model)
        assertNull(d.battery)
        assertNull(d.cpuTempC)
        assertNull(d.storageTotalMb)
        assertNull(d.heaterOn)
        assertEquals(parseDeviceInfo(null), d)
    }

    @Test
    fun overTemperatureFromEitherFlag() {
        assertTrue(parseDeviceInfo(JSONObject("""{"pi_status":{"battery_overtemp":true}}""")).overTemp)
    }

    @Test
    fun focusTargetsStayInRange() {
        assertEquals(1655, focusTarget(1555, 100, 2600))
        assertEquals(0, focusTarget(3, -5, 2600))
        assertEquals(2600, focusTarget(2590, 20, 2600))
    }
}
