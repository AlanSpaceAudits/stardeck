package com.spaceaudits.stardeck

import org.junit.Assert.assertEquals
import org.junit.Test

class ReticleTest {
    @Test
    fun s50PixelScaleAndField() {
        val scale = arcsecPerPixel(2.9, 250.0) // S50: 2.9 µm pixels, 250 mm focal length
        assertEquals(2.3927, scale, 0.0005)
        assertEquals(43.07, 1080 * scale / 60, 0.05) // arcminutes across the 1080-pixel side
    }

    @Test
    fun crossingTimeFollowsDeclination() {
        assertEquals(15.0411, SIDEREAL_ARCSEC_PER_S, 0.0001)
        assertEquals(39.89, crossingSeconds(600.0, 0.0), 0.01)
        assertEquals(79.78, crossingSeconds(600.0, 60.0), 0.02)
    }
}
