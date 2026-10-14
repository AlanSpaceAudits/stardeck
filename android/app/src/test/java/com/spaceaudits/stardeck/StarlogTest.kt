package com.spaceaudits.stardeck

import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.siderealTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class StarlogTest {
    private val now = 1791849600000L // 2026-10-13 00:00 UT
    private val jd = now / 86400000.0 + 2440587.5
    private val catalog by lazy { StarCatalog(File("src/main/assets/stars.tsv").readText(), jd) }

    // Mean sidereal time against Astronomy Engine's apparent sidereal time: they differ by the equation of the equinoxes (< 0.01°).
    @Test
    fun siderealTimeAgreesWithAstronomyEngine() {
        val gast = siderealTime(Time.fromMillisecondsSince1970(now)) * 15.0
        assertEquals(gast, meanSiderealDeg(jd), 0.01)
    }

    @Test
    fun formats() {
        assertEquals("+12°30′00.0″", fmtDeg(12.5, dms = true))
        assertEquals("-0°00′36.0″", fmtDeg(-0.01, dms = true))
        assertEquals("+12.5000°", fmtDeg(12.5, dms = false))
        assertEquals("06h45m09.00s", fmtRa(6.7525, dms = true))
    }

    // Same check as the PC app: point due south at Vega's meridian altitude and log the crossing.
    @Test
    fun logsVegaWhenItCrossesTheCentre() {
        val vega = catalog.stars.first { it.name == "Vega" }
        val (az, lat, lon) = Triple(180.0, 40.0, 0.0)
        val el = 90.0 - lat + vega.dec
        fun centreAt(unix: Double) = eqFromHorizontal(az, el, unix / 86400.0 + 2440587.5, lat, lon)
        var t0 = now / 1000.0
        repeat(3) { t0 += eastOf(vega.ra, centreAt(t0).first) * 3600.0 / SIDEREAL_RATE }
        assertEquals(vega.dec, centreAt(t0).second, 1e-6)
        assertEquals("Vega", catalog.upcoming(centreAt(t0 - 30), 0.35, 6.0, 600.0)[0].first.name)

        val out = ByteArrayOutputStream()
        val log = StarLog(catalog, out)
        // pressure 0: no refraction, so the elevation is the true altitude
        log.step(t0 - 5, az, el, lat, lon, 10.0, 0.0, 0.35, 6.0)
        log.step(t0 + 3, az, el, lat, lon, 10.0, 0.0, 0.35, 6.0)
        // a slew sweeps past stars; none of them is a crossing
        log.step(t0 + 4, az + 30, el, lat, lon, 10.0, 0.0, 0.35, 6.0)
        val rows = out.toString().lines().filter { it.isNotBlank() }
        assertEquals(rows.toString(), 2, rows.size)
        assertTrue(rows[1].contains(",Vega,"))
        assertEquals(t0, log.crossings.single().utcMillis / 1000.0, 0.01)
        assertEquals(rows[0].split(',').size, rows[1].split(',').size)
    }
}
