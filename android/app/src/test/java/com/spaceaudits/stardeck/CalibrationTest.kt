package com.spaceaudits.stardeck

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private val appState = JSONObject("""{
        "View":{"state":"working","stage":"Initialise",
            "Initialise":{"state":"working","route":[],"3PPA":{"state":"working","percent":42.5,"route":[]}},
            "DarkLibrary":{"state":"fail","percent":10,"code":207,"error":"fail to operate"}}
    }""")

    @Test
    fun findsNestedStages() {
        val ppa = parseStage(findStage(appState, "3PPA"))!!
        assertTrue(ppa.working)
        assertEquals(42.5, ppa.percent!!, 0.0)
        assertEquals("working  43%", ppa.line())
        val dark = parseStage(findStage(appState, "DarkLibrary"))!!
        assertEquals(207, dark.code)
        assertEquals("fail  10%  code 207  fail to operate", dark.line())
        assertNull(findStage(appState, "EqModePA"))
        assertNull(parseStage(null))
    }

    @Test
    fun polarError() {
        val e = parsePolarError(JSONObject("""{"state":"working","x_arcsec":120.4,"y_arcsec":-30,"x":0.5,"y":-0.8}"""))!!
        assertEquals(120.4, e.xArcsec!!, 0.0)
        assertTrue(e.aligned)
        assertTrue(e.line(), e.line().contains("error x 120\"  y -30\""))
        assertTrue(e.line(), e.line().contains("offset x +0.50  y -0.80  aligned"))
        assertFalse(parsePolarError(JSONObject("""{"x":1.5,"y":0}"""))!!.aligned)
        assertNull(parsePolarError(JSONObject("""{"state":"idle"}""")))
    }

    @Test
    fun polarAlignParamsMatchOfficialApp() {
        val start = polarAlignParams(true, 3)
        assertTrue(start.getBoolean("restart"))
        assertEquals(3, start.getInt("dec_pos_index"))
        assertEquals(5, polarAlignParams(true, 9).getInt("dec_pos_index"))
        val again = polarAlignParams(false, 3)
        assertFalse(again.getBoolean("restart"))
        assertFalse(again.has("dec_pos_index"))
    }

    @Test
    fun versionAndUpdateFlag() {
        val v = parseVersionInfo(JSONObject("""{"device":{"firmware_ver_int":2846,"firmware_ver_string":"8.46","svr_ver_int":1,"svr_ver_string":"x"},"client":{"is_master":true}}"""))
        assertEquals("8.46", v.firmware)
        assertEquals(2846, v.firmwareInt)
        assertEquals(FirmwareUpdate.NONE, v.update)
        assertEquals(FirmwareUpdate.OFFERED, parseVersionInfo(JSONObject("""{"device":{"firmware_ver_int":2700}}""")).update)
        assertEquals(FirmwareUpdate.REQUIRED, parseVersionInfo(JSONObject("""{"device":{"firmware_ver_int":2600}}""")).update)
        assertEquals(FirmwareUpdate.NONE, parseVersionInfo(JSONObject("""{"device":{"firmware_ver_int":2600},"client":{"is_master":false}}""")).update)
        assertEquals(FirmwareUpdate.NONE, parseVersionInfo(null).update)
        assertTrue(v.lines().joinToString("\n").contains("firmware 8.46 (2846)"))
    }
}
