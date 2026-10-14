package com.spaceaudits.stardeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GotoTest {
    @Test
    fun parsesDecimalAndSexagesimal() {
        assertEquals(5.5, parseSexagesimal("5.5")!!, 1e-9)
        assertEquals(5.5, parseSexagesimal("5 30 00")!!, 1e-9)
        assertEquals(5.5, parseSexagesimal("5h30m00s")!!, 1e-9)
        assertEquals(-5.391111, parseSexagesimal("-5 23 28")!!, 1e-6)
        assertEquals(-5.391111, parseSexagesimal("-5:23:28")!!, 1e-6)
        assertNull(parseSexagesimal("abc"))
    }

    @Test
    fun labelGoesInFrontOfTheEpoch() {
        Labels.current = "NGC 2264 cone"
        assertEquals("NGC_2264_cone_1792012593.png", epochName(1792012593000, "png"))
        Labels.current = ""
        assertEquals("1792012593.png", epochName(1792012593000, "png"))
    }
}
