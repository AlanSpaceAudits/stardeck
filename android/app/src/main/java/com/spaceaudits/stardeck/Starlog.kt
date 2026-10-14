package com.spaceaudits.stardeck

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/*
 * Starlog: the stars that cross the centre of the field while the telescope holds an azimuth and elevation.
 * A star crosses when its right ascension equals the right ascension of the field centre; its declination
 * offset from the centre at that moment is its closest distance to the centre. Same method as the PC app.
 *
 * Star data: HYG database v4.1 by David Nash, CC-BY-SA-4.0, https://codeberg.org/astronexus/hyg
 * (trimmed by tools/make_stars.py).
 */

/** Sidereal seconds per solar second. Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 12, eq. 12.4. */
const val SIDEREAL_RATE = 360.98564736629 / 360.0

/** A larger change of azimuth or elevation between two readings is a slew: no crossings are taken from it. */
const val STILL_DEG = 0.02

const val STARLOG_RADIUS_DEG = 0.35

class Star(
    val hip: String, val hd: String, val hr: String, val name: String, val bf: String, val con: String,
    val raJ2000: Double, val decJ2000: Double, val mag: Double, val spect: String,
    /** hours, mean equinox of date, proper motion applied */
    val ra: Double,
    /** degrees, mean equinox of date, proper motion applied */
    val dec: Double,
) {
    fun label() = when {
        name.isNotEmpty() -> name
        bf.isNotEmpty() -> bf
        hip.isNotEmpty() -> "HIP $hip"
        hd.isNotEmpty() -> "HD $hd"
        else -> "HR $hr"
    }
}

/** Mean sidereal time at Greenwich in degrees. Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 12, eq. 12.4. */
fun meanSiderealDeg(jd: Double): Double {
    val t = (jd - 2451545.0) / 36525.0
    val theta = 280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0
    return ((theta % 360.0) + 360.0) % 360.0
}

/** Approximate air pressure in millibars at a height above sea level in metres. */
fun pressureFromHeightMb(heightM: Double) = 1013.25 * (1.0 - (0.0065 * heightM) / 288.15).pow(5.255)

/**
 * Atmospheric refraction in degrees at an apparent altitude, for a temperature in °C and a pressure in millibars.
 * Urban, S. E., & Seidelmann, P. K. (Eds.). (2013). Explanatory Supplement to the Astronomical Almanac (3rd ed.).
 * University Science Books, sec. 7.2.7.3, eq. 7.90.
 */
fun refractionDeg(apparentAltDeg: Double, tempC: Double, pressureMb: Double): Double {
    val h = max(apparentAltDeg, -1.0)
    return 0.28 * pressureMb / (tempC + 273.0) * 0.0167 / tan(Math.toRadians(h + 7.31 / (h + 4.4)))
}

/**
 * RA (hours) and Dec (degrees) of date for azimuth (from north through east) and true altitude in degrees.
 * Longitude is positive east. Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 13.
 */
fun eqFromHorizontal(azDeg: Double, altDeg: Double, jd: Double, latDeg: Double, lonEastDeg: Double): Pair<Double, Double> {
    val (az, alt, lat) = Triple(Math.toRadians(azDeg), Math.toRadians(altDeg), Math.toRadians(latDeg))
    val dec = asin((sin(lat) * sin(alt) + cos(lat) * cos(alt) * cos(az)).coerceIn(-1.0, 1.0))
    val hourAngle = atan2(-sin(az) * cos(alt), cos(lat) * sin(alt) - sin(lat) * cos(alt) * cos(az))
    val ra = Math.toRadians(meanSiderealDeg(jd) + lonEastDeg) - hourAngle
    return (((Math.toDegrees(ra) % 360.0) + 360.0) % 360.0) / 15.0 to Math.toDegrees(dec)
}

/** Hours of right ascension from the centre to the star. Positive: the star is east of the centre and will cross. */
fun eastOf(starRa: Double, centreRa: Double) = ((starRa - centreRa + 12.0) % 24.0 + 24.0) % 24.0 - 12.0

/** All stars, sorted by declination of date, with coordinates brought to `jd`. */
class StarCatalog(tsv: String, jd: Double) {
    val stars: List<Star>

    init {
        val years = (jd - 2451545.0) / 365.25
        stars = tsv.lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 12) return@mapNotNull null
            val raJ = f[6].toDoubleOrNull() ?: return@mapNotNull null
            val decJ = f[7].toDoubleOrNull() ?: return@mapNotNull null
            val pmRa = f[8].toDoubleOrNull() ?: return@mapNotNull null
            val pmDec = f[9].toDoubleOrNull() ?: return@mapNotNull null
            // proper motion is in milliarcseconds per year; the RA term is on the sky (includes cos Dec)
            val decNow = decJ + pmDec / 3.6e6 * years
            val raNow = raJ + pmRa / 3.6e6 * years / max(cos(Math.toRadians(decJ)), 1e-6) / 15.0
            val (ra, dec) = precessFromJ2000(raNow, decNow, jd)
            Star(f[0], f[1], f[2], f[3], f[4], f[5], raJ, decJ, f[10].toDoubleOrNull() ?: return@mapNotNull null, f[11], ra, dec)
        }.sortedBy { it.dec }.toList()
    }

    /** Stars within `radiusDeg` of the centre declination and not fainter than `magLimit`. */
    fun band(centreDec: Double, radiusDeg: Double, magLimit: Double): Sequence<Star> {
        var lo = 0
        var hi = stars.size
        while (lo < hi) { // first star with dec >= centreDec - radius
            val mid = (lo + hi) / 2
            if (stars[mid].dec < centreDec - radiusDeg) lo = mid + 1 else hi = mid
        }
        return (lo until stars.size).asSequence().map { stars[it] }
            .takeWhile { it.dec <= centreDec + radiusDeg }.filter { it.mag <= magLimit }
    }

    /** Stars that cross in the next `withinS` seconds if the pointing stays fixed: (star, seconds from now), soonest first. */
    fun upcoming(centre: Pair<Double, Double>, radiusDeg: Double, magLimit: Double, withinS: Double): List<Pair<Star, Double>> =
        band(centre.second, radiusDeg, magLimit)
            .map { it to eastOf(it.ra, centre.first) * 3600.0 / SIDEREAL_RATE }
            .filter { it.second > 0 && it.second <= withinS }
            .sortedBy { it.second }.toList()
}

/** One logged crossing. Angles in degrees except RA in hours. */
class Crossing(
    val utcMillis: Long, val star: Star, val az: Double, val el: Double, val lat: Double, val lon: Double,
    val centreRa: Double, val centreDec: Double, val decOffset: Double, val refraction: Double,
)

/** Writes crossings as CSV rows to `out` (same columns as the PC app) and keeps them for display. */
class StarLog(private val catalog: StarCatalog, private val out: OutputStream) {
    private class Reading(val unix: Double, val az: Double, val el: Double, val centre: Pair<Double, Double>)
    private var prev: Reading? = null
    val crossings = mutableListOf<Crossing>()

    init {
        out.write(("utc,lat_deg,lon_deg,az_deg,el_deg,temp_c,pressure_mb,refraction_deg,centre_ra_h,centre_dec_deg,hip,hd,hr,name," +
            "bayer_flamsteed,constellation,mag,spectral_type,star_ra_h,star_dec_deg,star_ra_j2000_h,star_dec_j2000_deg,dec_offset_deg\n").toByteArray())
        out.flush()
    }

    /** Takes a pointing reading; returns the stars that crossed the centre since the reading before it. */
    fun step(unixS: Double, az: Double, el: Double, lat: Double, lon: Double, tempC: Double, pressureMb: Double, radiusDeg: Double, magLimit: Double): List<Crossing> {
        val refraction = refractionDeg(el, tempC, pressureMb)
        val centre = eqFromHorizontal(az, el - refraction, unixS / 86400.0 + 2440587.5, lat, lon)
        val p = prev
        prev = Reading(unixS, az, el, centre)
        if (p == null) return emptyList()
        val azStep = (((az - p.az + 180.0) % 360.0 + 360.0) % 360.0 - 180.0) * cos(Math.toRadians(el))
        if (abs(azStep) > STILL_DEG || abs(el - p.el) > STILL_DEG) return emptyList()
        val found = catalog.band(centre.second, radiusDeg, magLimit).mapNotNull { s ->
            val before = eastOf(s.ra, p.centre.first)
            val after = eastOf(s.ra, centre.first)
            if (!(before > 0 && after <= 0 && before - after < 1.0)) return@mapNotNull null
            val t = p.unix + (unixS - p.unix) * before / (before - after)
            Crossing((t * 1000).toLong(), s, az, el, lat, lon, centre.first, centre.second, s.dec - centre.second, refraction)
        }.toList()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (c in found) {
            val s = c.star
            val row = listOf(
                iso.format(Date(c.utcMillis)), "%.6f".format(Locale.US, lat), "%.6f".format(Locale.US, lon), "%.4f".format(Locale.US, az),
                "%.4f".format(Locale.US, el), "$tempC", "$pressureMb", "%.5f".format(Locale.US, c.refraction),
                "%.6f".format(Locale.US, c.centreRa), "%.5f".format(Locale.US, c.centreDec), s.hip, s.hd, s.hr, s.name, s.bf, s.con,
                "${s.mag}", s.spect, "%.6f".format(Locale.US, s.ra), "%.5f".format(Locale.US, s.dec),
                "%.6f".format(Locale.US, s.raJ2000), "%.5f".format(Locale.US, s.decJ2000), "%+.5f".format(Locale.US, c.decOffset),
            )
            out.write((row.joinToString(",") + "\n").toByteArray())
        }
        if (found.isNotEmpty()) out.flush()
        crossings += found
        return found
    }

    fun close() = out.close()
}

/** Degrees as decimal or as degrees, minutes, seconds. */
fun fmtDeg(v: Double, dms: Boolean, signed: Boolean = true, digits: Int = 4): String {
    if (!dms) return if (signed) "%+.${digits}f°".format(Locale.US, v) else "%.${digits}f°".format(Locale.US, v)
    val sign = if (v < 0) "-" else if (signed) "+" else ""
    var s = abs(v) * 3600.0
    val d = (s / 3600).toInt(); s -= d * 3600
    val m = (s / 60).toInt(); s -= m * 60
    return "%s%d°%02d′%04.1f″".format(Locale.US, sign, d, m, s)
}

/** Right ascension in hours as decimal hours or as hours, minutes, seconds. */
fun fmtRa(hours: Double, dms: Boolean): String {
    if (!dms) return "%.5fh".format(Locale.US, hours)
    var s = hours * 3600.0
    val h = (s / 3600).toInt(); s -= h * 3600
    val m = (s / 60).toInt(); s -= m * 60
    return "%02dh%02dm%05.2fs".format(Locale.US, h, m, s)
}
