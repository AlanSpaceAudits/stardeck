package com.spaceaudits.stardeck

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deep-sky object lookup and J2000 to date-of-observation precession.
 *
 * Catalogue data: OpenNGC by Mattia Verga, CC-BY-SA-4.0,
 * https://github.com/mattiaverga/OpenNGC (trimmed by tools/make_catalog.py).
 */
class SkyObject(val name: String, val other: String, val kind: String, val raHours: Double, val decDeg: Double, val mag: String) {
    val key = "|${squash(name)}|${squash(other)}|"
    val alias get() = other.substringBefore('|')
}

private fun squash(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

class Catalog(tsv: String) {
    private val objects = tsv.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6) null else SkyObject(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: return@mapNotNull null, f[4].toDoubleOrNull() ?: return@mapNotNull null, f[5])
    }.toList()

    /** The Messier objects. */
    fun messier(): List<SkyObject> = objects.filter { it.other.startsWith("M ") }

    /** Objects whose name or alias contains the query; exact name matches first, then prefixes. */
    fun search(query: String, limit: Int): List<SkyObject> {
        val q = squash(query)
        if (q.isEmpty()) return emptyList()
        return objects.filter { q in it.key }
            .sortedBy { if ("|$q|" in it.key) 0 else if ("|$q" in it.key) 1 else 2 }
            .take(limit)
    }
}

fun julianDateNow() = System.currentTimeMillis() / 86400000.0 + 2440587.5

/**
 * Precesses J2000.0 mean coordinates to the mean equinox of [jd]; returns RA hours, Dec degrees.
 * Meeus, J. (1998). Astronomical Algorithms (2nd ed.), ch. 21, eq. 21.2 and 21.4.
 */
fun precessFromJ2000(raHours: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
    val t = (jd - 2451545.0) / 36525.0
    fun arcsec(a: Double) = Math.toRadians(a / 3600.0)
    val zeta = arcsec(2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t)
    val z = arcsec(2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t)
    val theta = arcsec(2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t)
    val ra = Math.toRadians(raHours * 15.0)
    val dec = Math.toRadians(decDeg)
    val a = cos(dec) * sin(ra + zeta)
    val b = cos(theta) * cos(dec) * cos(ra + zeta) - sin(theta) * sin(dec)
    val c = sin(theta) * cos(dec) * cos(ra + zeta) + cos(theta) * sin(dec)
    val raNew = (Math.toDegrees(atan2(a, b) + z) % 360.0 + 360.0) % 360.0 / 15.0
    return raNew to Math.toDegrees(asin(c))
}
