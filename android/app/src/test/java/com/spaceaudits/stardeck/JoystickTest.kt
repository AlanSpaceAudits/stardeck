package com.spaceaudits.stardeck

import org.junit.Assert.assertEquals
import org.junit.Test

class JoystickTest {
    @Test
    fun deflectionMapsLikeTheOfficialStick() {
        assertEquals(0, stickPercent(0.5))
        assertEquals(1, stickPercent(5.0))
        assertEquals(34, stickPercent(40.0))
        assertEquals(76, stickPercent(70.0))
        assertEquals(100, stickPercent(95.0))
    }

    @Test
    fun directionsMatchTheScopeAngles() {
        assertEquals(SLEW_RIGHT, stickAngle(10f, 0f))
        assertEquals(SLEW_UP, stickAngle(0f, -10f)) // screen y grows downward
        assertEquals(SLEW_LEFT, stickAngle(-10f, 0f))
        assertEquals(SLEW_DOWN, stickAngle(0f, 10f))
    }
}
