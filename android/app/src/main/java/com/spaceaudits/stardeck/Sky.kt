package com.spaceaudits.stardeck

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.angleFromSun
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon

/** Solar-system positions and altitudes, from the Astronomy Engine library (Don Cross, MIT licence). */
object Sky {
    val PLANETS = listOf(
        "Mercury" to Body.Mercury, "Venus" to Body.Venus, "Mars" to Body.Mars, "Jupiter" to Body.Jupiter,
        "Saturn" to Body.Saturn, "Uranus" to Body.Uranus, "Neptune" to Body.Neptune,
    )

    /** Solar-system targets closer to the Sun than this need the solar filter tick. */
    const val SUN_GUARD_DEG = 20.0

    fun time(utcMillis: Long): Time = Time.fromMillisecondsSince1970(utcMillis)

    /** Apparent RA (hours) and Dec (degrees) of date. */
    fun raDec(body: Body, utcMillis: Long, lat: Double = 0.0, lon: Double = 0.0): Pair<Double, Double> {
        val e = equator(body, time(utcMillis), Observer(lat, lon, 0.0), EquatorEpoch.OfDate, Aberration.Corrected)
        return e.ra to e.dec
    }

    /** Altitude in degrees, no refraction. Longitude is positive east. */
    fun altitude(raHours: Double, decDeg: Double, utcMillis: Long, lat: Double, lon: Double): Double =
        horizon(time(utcMillis), Observer(lat, lon, 0.0), raHours, decDeg, Refraction.None).altitude

    fun degreesFromSun(body: Body, utcMillis: Long): Double = angleFromSun(body, time(utcMillis))
}
