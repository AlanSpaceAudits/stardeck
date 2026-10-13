package com.spaceaudits.stardeck

import io.github.cosinekitty.astronomy.Body
import org.junit.Assert.assertEquals
import org.junit.Test

class SkyTest {
    // Reference: JPL Horizons, apparent RA/Dec, geocentric, 2026-Oct-13 00:00 UT.
    private val utc = 1791849600000L

    @Test
    fun planetsMatchHorizons() {
        for ((body, raDeg, decDeg) in listOf(Triple(Body.Jupiter, 144.21573, 14.89053), Triple(Body.Mars, 131.29327, 19.31479), Triple(Body.Saturn, 10.84219, 1.71517))) {
            val (ra, dec) = Sky.raDec(body, utc)
            assertEquals(raDeg, ra * 15, 0.02)
            assertEquals(decDeg, dec, 0.02)
        }
    }

    @Test
    fun altitudeOfPoleIsLatitude() {
        assertEquals(44.0, Sky.altitude(5.0, 90.0, utc, 44.0, -116.0), 0.01)
    }
}
