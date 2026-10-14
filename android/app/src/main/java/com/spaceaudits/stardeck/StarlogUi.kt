package com.spaceaudits.stardeck

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Starlog state that outlives the panel being shown. */
class StarlogState {
    var catalog by mutableStateOf<StarCatalog?>(null)
    var loading by mutableStateOf(false)
    var log by mutableStateOf<StarLog?>(null)
    var fileName by mutableStateOf("")
    var error by mutableStateOf("")
    var radius by mutableStateOf(STARLOG_RADIUS_DEG.toString())
    var mag by mutableStateOf("10")
    var tempC by mutableStateOf("10")
    var pressureMb by mutableStateOf("0")
    val crossings = mutableStateListOf<Crossing>()

    fun radiusDeg() = radius.toDoubleOrNull() ?: STARLOG_RADIUS_DEG
    fun magLimit() = mag.toDoubleOrNull() ?: 10.0
    fun temp() = tempC.toDoubleOrNull() ?: 10.0
    fun pressure() = pressureMb.toDoubleOrNull() ?: 0.0

    suspend fun ensureCatalog(context: Context) {
        if (catalog != null || loading) return
        loading = true
        catalog = withContext(Dispatchers.Default) {
            StarCatalog(context.assets.open("stars.tsv").bufferedReader().readText(), System.currentTimeMillis() / 86400000.0 + 2440587.5)
        }
        loading = false
    }

    fun start(context: Context, utcMillis: Long) {
        val cat = catalog ?: return
        val name = "starlog_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(utcMillis)) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StarDeck")
        }
        runCatching {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("could not create $name")
            log = StarLog(cat, context.contentResolver.openOutputStream(uri)!!)
            fileName = "Download/StarDeck/$name"
            crossings.clear()
            error = ""
        }.onFailure { error = "starlog: ${it.message}" }
    }

    fun stop() {
        runCatching { log?.close() }
        log = null
    }

    /** One pointing reading from the scope. `site` is latitude, east longitude. */
    fun onPointing(pointing: JSONObject?, site: Pair<Double, Double>?, utcMillis: Long) {
        val l = log ?: return
        val az = pointing?.optDouble("az")?.takeIf { !it.isNaN() } ?: return
        val el = pointing.optDouble("alt").takeIf { !it.isNaN() } ?: return
        val (lat, lon) = site ?: return
        runCatching { crossings += l.step(utcMillis / 1000.0, az, el, lat, lon, temp(), pressure(), radiusDeg(), magLimit()) }
            .onFailure { error = "starlog: ${it.message}"; stop() }
    }

    /** Field centre (RA hours, Dec degrees of date) for the current pointing. */
    fun centre(pointing: JSONObject?, site: Pair<Double, Double>?, utcMillis: Long): Pair<Double, Double>? {
        val az = pointing?.optDouble("az")?.takeIf { !it.isNaN() } ?: return null
        val el = pointing.optDouble("alt").takeIf { !it.isNaN() } ?: return null
        val (lat, lon) = site ?: return null
        return eqFromHorizontal(az, el - refractionDeg(el, temp(), pressure()), utcMillis / 86400000.0 + 2440587.5, lat, lon)
    }
}

private val hms = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

@Composable
fun StarlogPanel(state: StarlogState, link: Link, gps: Gps, site: Pair<Double, Double>?) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { state.ensureCatalog(context) }
    val dms = Prefs.dms
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Logs each star that crosses the field centre while the telescope holds one azimuth and elevation. Leave tracking off.", style = MaterialTheme.typography.bodySmall)
        when {
            state.loading -> Text("loading the star list…")
            site == null -> Text("No location: wait for a GPS lock or send a location to the scope.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.radius, { state.radius = it }, Modifier.weight(1f), singleLine = true, label = { Text("within, °") })
            OutlinedTextField(state.mag, { state.mag = it }, Modifier.weight(1f), singleLine = true, label = { Text("to mag") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.tempC, { state.tempC = it }, Modifier.weight(1f), singleLine = true, label = { Text("air, °C") })
            OutlinedTextField(state.pressureMb, { state.pressureMb = it }, Modifier.weight(1f), singleLine = true, label = { Text("pressure, mbar (0 = off)") })
        }
        val fix = gps.fix
        if (fix != null && fix.hasAltitude()) {
            Button({ state.pressureMb = "%.1f".format(Locale.US, pressureFromHeightMb(fix.altitude)) }) {
                Text("Pressure from GPS height (%.0f m)".format(fix.altitude))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.log == null) {
                Button({ state.start(context, gps.utcMillis()) }, enabled = state.catalog != null && site != null) { Text("Start starlog") }
            } else {
                Button({ state.stop() }) { Text("Stop starlog") }
            }
        }
        if (state.log != null) Text("${state.crossings.size} crossings  →  ${state.fileName}", style = MaterialTheme.typography.bodySmall)
        if (state.error.isNotEmpty()) Text(state.error)

        // the next crossings for the current pointing
        val cat = state.catalog
        val centre = state.centre(link.pointing, site, gps.utcMillis())
        if (cat != null && centre != null) {
            Text("Field centre  RA ${fmtRa(centre.first, dms)}  Dec ${fmtDeg(centre.second, dms)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            val next = cat.upcoming(centre, state.radiusDeg(), state.magLimit(), 900.0).take(8)
            Text("Next 15 minutes", fontWeight = FontWeight.Bold)
            if (next.isEmpty()) Text("none", style = MaterialTheme.typography.bodySmall)
            for ((s, t) in next) {
                Text(
                    "in %d:%02d  %s  mag %.2f  off %s".format(t.toInt() / 60, t.toInt() % 60, s.label(), s.mag, fmtDeg(s.dec - centre.second, dms, digits = 3)),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
        }

        if (state.crossings.isNotEmpty()) Text("Crossings, newest first", fontWeight = FontWeight.Bold)
        for (c in state.crossings.asReversed()) CrossingCard(c, dms)
    }
}

@Composable
private fun CrossingCard(c: Crossing, dms: Boolean) {
    val s = c.star
    val ids = listOfNotNull(
        s.bf.ifEmpty { null }, s.hip.ifEmpty { null }?.let { "HIP $it" }, s.hd.ifEmpty { null }?.let { "HD $it" },
        s.hr.ifEmpty { null }?.let { "HR $it" }, s.con.ifEmpty { null },
    ).joinToString(", ")
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("${hms.format(Date(c.utcMillis))} UTC  ${s.label()}", fontWeight = FontWeight.Bold)
        if (ids.isNotEmpty()) Text(ids, style = MaterialTheme.typography.bodySmall)
        val mono = Modifier
        val lines = listOf(
            "mag %.2f  %s  off centre %s".format(s.mag, s.spect.ifEmpty { "?" }, fmtDeg(c.decOffset, dms, digits = 4)),
            "Az ${fmtDeg(c.az, dms, signed = false)}  El ${fmtDeg(c.el, dms)}",
            "RA ${fmtRa(s.ra, dms)}  Dec ${fmtDeg(s.dec, dms)}  (of date)",
            "RA ${fmtRa(s.raJ2000, dms)}  Dec ${fmtDeg(s.decJ2000, dms)}  (J2000)",
            "Lat ${fmtDeg(c.lat, dms, digits = 5)}  Lon ${fmtDeg(c.lon, dms, digits = 5)}  refr ${fmtDeg(c.refraction, dms, signed = false, digits = 4)}",
        )
        for (l in lines) Text(l, mono, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
