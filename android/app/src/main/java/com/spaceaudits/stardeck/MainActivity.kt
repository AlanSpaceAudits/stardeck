package com.spaceaudits.stardeck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// scope_speed_move angles
const val SLEW_UP = 90
const val SLEW_DOWN = 270
const val SLEW_LEFT = 180
const val SLEW_RIGHT = 0

class Link(private val keyPem: String) {
    var status by mutableStateOf("not connected")
    var connected by mutableStateOf(false)
    var pointing by mutableStateOf<JSONObject?>(null)
    var tracking by mutableStateOf(false)
    var ip by mutableStateOf<String?>(null)
    var lastReply by mutableStateOf("")
    /** Every on/off setting the scope reports, including ones the official app hides. */
    var settings by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var stackSettings by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var scopeLocation by mutableStateOf("")
    /** "host:port"; when set, each pointing reading is also sent there as one JSON datagram. */
    @Volatile var telemetryTarget = ""
    @Volatile private var refresh = true

    private var commands: ExecutorService? = null
    private var cmdScope: Scope? = null
    @Volatile private var generation = 0

    fun connect(host: String) {
        disconnect()
        val gen = ++generation
        status = "connecting to $host"
        val exec = Executors.newSingleThreadExecutor().also { commands = it }
        exec.execute {
            try {
                // the scope listens on IPv4 only, and .local names resolve to IPv6 first
                val addr = InetAddress.getAllByName(host).first { it is Inet4Address }.hostAddress!!
                cmdScope = Scope(addr, keyPem)
                ip = addr
                connected = true
                status = "connected to $host"
                // Status polling runs on its own connection so it never delays commands.
                thread { poll(addr, gen) }
            } catch (e: Exception) {
                status = "connect failed: ${e.message}"
            }
        }
    }

    private fun poll(addr: String, gen: Int) {
        try {
            Scope(addr, keyPem).use { s ->
                DatagramSocket().use { udp ->
                    var lastSettings = 0L
                    while (gen == generation) {
                        val p = s.call("scope_get_state").optJSONObject("result")
                        pointing = p
                        tracking = s.call("scope_get_track_state").optBoolean("result")
                        if (p != null) sendTelemetry(udp, p)
                        if (refresh || System.currentTimeMillis() - lastSettings > 15000) {
                            refresh = false
                            lastSettings = System.currentTimeMillis()
                            val state = s.call("get_device_state", JSONObject().put("keys", JSONArray().put("setting").put("location_lon_lat"))).optJSONObject("result") ?: JSONObject()
                            val setting = state.optJSONObject("setting") ?: JSONObject()
                            settings = bools(setting)
                            stackSettings = bools(setting.optJSONObject("stack") ?: JSONObject())
                            scopeLocation = state.optJSONArray("location_lon_lat")?.let { "lat %.4f, lon %.4f".format(it.optDouble(1), it.optDouble(0)) } ?: ""
                        }
                        Thread.sleep(500)
                    }
                }
            }
        } catch (e: Exception) {
            if (gen == generation) {
                connected = false
                status = "connection lost: ${e.message}"
            }
        }
    }

    private fun sendTelemetry(udp: DatagramSocket, p: JSONObject) {
        val target = telemetryTarget.trim()
        val port = target.substringAfterLast(':', "").toIntOrNull() ?: return
        runCatching {
            val bytes = JSONObject(p.toString()).put("utc_ms", System.currentTimeMillis()).toString().toByteArray()
            udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(target.substringBeforeLast(':')), port))
        }
    }

    private fun bools(o: JSONObject): Map<String, Boolean> =
        o.keys().asSequence().filter { o.opt(it) is Boolean }.sorted().associateWith { o.getBoolean(it) }

    fun send(method: String, params: Any? = null, quiet: Boolean = false) {
        commands?.execute {
            try {
                val r = cmdScope?.call(method, params) ?: return@execute
                if (!quiet) refresh = true
                if (!quiet || r.optInt("code") != 0) lastReply = "$method: ${r.optString("error").ifEmpty { "code ${r.optInt("code")}" }}"
            } catch (e: Exception) {
                lastReply = "$method: ${e.message}"
            }
        }
    }

    /** Sends the phone's GPS position and GPS time to the scope. The scope's clock takes whole seconds. */
    fun syncGps(gps: Gps) {
        val fix = gps.fix ?: return
        send("set_user_location", JSONObject().put("lon", fix.longitude).put("lat", fix.latitude).put("force", false))
        val c = Calendar.getInstance().apply { timeInMillis = gps.utcMillis() }
        send("pi_set_time", JSONArray().put(JSONObject()
            .put("year", c.get(Calendar.YEAR)).put("mon", c.get(Calendar.MONTH) + 1).put("day", c.get(Calendar.DAY_OF_MONTH))
            .put("hour", c.get(Calendar.HOUR_OF_DAY)).put("min", c.get(Calendar.MINUTE)).put("sec", c.get(Calendar.SECOND))
            .put("time_zone", TimeZone.getDefault().id)))
    }

    fun disconnect() {
        generation++
        commands?.shutdownNow()
        commands = null
        runCatching { cmdScope?.close() }
        cmdScope = null
        connected = false
        ip = null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = Link(assets.open("seestar_key.pem").bufferedReader().readText())
        val catalog = Catalog(assets.open("catalog.tsv").bufferedReader().readText())
        val gps = Gps(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(link, catalog, gps) }
            }
        }
    }
}

@Composable
fun Screen(link: Link, catalog: Catalog, gps: Gps) {
    val context = LocalContext.current
    var host by remember { mutableStateOf("SeeStar.local") }
    var video by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf(true) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(40f) }
    var held by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var shutterMs by remember { mutableStateOf("10") }
    var lapseSeconds by remember { mutableStateOf("5") }
    var lapseMinutes by remember { mutableStateOf("5") }
    var lapseRunning by remember { mutableStateOf(false) }
    var lapseSaved by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    var telemetry by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<PlayerView?>(null) }
    var tick by remember { mutableLongStateOf(0L) } // redraws the HUD clock

    // GPS: ask once, then listen while the screen is shown.
    var gpsGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val askGps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { gpsGranted = it }
    DisposableEffect(gpsGranted) {
        if (gpsGranted) gps.start()
        onDispose { if (gpsGranted) gps.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(100)
        }
    }

    // Resend while a direction is held; the scope stops on percent 0.
    LaunchedEffect(held) {
        val angle = held ?: return@LaunchedEffect
        try {
            while (true) {
                link.send("scope_speed_move", JSONObject().put("percent", speed.toInt()).put("angle", angle).put("level", 1).put("dur_sec", 3), quiet = true)
                delay(200)
            }
        } finally {
            link.send("scope_speed_move", JSONObject().put("percent", 0).put("angle", 0).put("level", 1).put("dur_sec", 3), quiet = true)
        }
    }

    fun snapshot(prefix: String): String? {
        val frame = (player?.videoSurfaceView as? TextureView)?.bitmap ?: return null
        val lines = if (hud) hudLines(link.pointing, gps) else null
        return savePng(context, burnHud(frame, flipH, flipV, lines), prefix, gps.utcMillis())
    }

    // App-side interval capture: one frame every interval, HUD burned in when it is on.
    LaunchedEffect(lapseRunning) {
        if (!lapseRunning) return@LaunchedEffect
        val interval = ((lapseSeconds.toDoubleOrNull() ?: 5.0).coerceAtLeast(0.2) * 1000).toLong()
        val minutes = lapseMinutes.toDoubleOrNull() ?: 0.0 // 0 = no end
        val start = System.currentTimeMillis()
        lapseSaved = 0
        while (minutes <= 0 || System.currentTimeMillis() - start < minutes * 60000) {
            runCatching { snapshot("lapse_") }.onSuccess { if (it != null) lapseSaved++ }.onFailure { note = "timelapse: ${it.message}" }
            delay(interval)
        }
        lapseRunning = false
    }

    Column(
        Modifier.systemBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, Modifier.weight(1f), singleLine = true, label = { Text("Host or IP") })
            Button({ if (link.connected) link.disconnect() else link.connect(host.trim()) }) {
                Text(if (link.connected) "Disconnect" else "Connect")
            }
        }
        Text(link.status)

        val lines = remember(tick, link.pointing, gps.fix) { hudLines(link.pointing, gps) }
        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 13.sp)

        // GPS lock indicator and sync
        val fix = gps.fix
        val gpsText = when {
            !gpsGranted -> "GPS: permission not granted"
            fix == null -> "GPS: no fix yet (${gps.satellitesSeen} satellites in view)"
            !gps.locked() -> "GPS: fix lost (${gps.satellitesSeen} satellites in view)"
            else -> "GPS: LOCKED  ±%.0f m  %d of %d satellites".format(fix.accuracy, gps.satellitesUsed, gps.satellitesSeen)
        }
        Text(gpsText, color = if (gpsGranted && gps.locked()) Color(0xFF7CFC8A) else Color(0xFFFFB74D))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!gpsGranted) Button({ askGps.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow GPS") }
            Button({ link.syncGps(gps) }, enabled = link.connected && gps.locked()) { Text("Send GPS to scope") }
        }
        if (link.scopeLocation.isNotEmpty()) Text("scope has ${link.scopeLocation}", style = MaterialTheme.typography.bodySmall)

        if (video && link.ip != null) {
            Box(Modifier.height(420.dp).aspectRatio(9f / 16f)) {
                Video("rtsp://${link.ip}:4554/stream", Modifier.fillMaxSize().graphicsLayer(scaleX = if (flipH) -1f else 1f, scaleY = if (flipV) -1f else 1f)) { player = it }
                if (hud) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val (arm, gap) = size.height / 20 to size.height / 100
                        val red = Color(0xFFFF3C3C)
                        drawLine(red, c - Offset(arm, 0f), c - Offset(gap, 0f), 3f)
                        drawLine(red, c + Offset(gap, 0f), c + Offset(arm, 0f), 3f)
                        drawLine(red, c - Offset(0f, arm), c - Offset(0f, gap), 3f)
                        drawLine(red, c + Offset(0f, gap), c + Offset(0f, arm), 3f)
                    }
                    Text(
                        lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 8.sp, lineHeight = 10.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0xA5000000)).padding(3.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ video = !video }) { Text(if (video) "Hide video" else "Video") }
            Checkbox(hud, { hud = it }); Text("HUD")
            Checkbox(flipH, { flipH = it }); Text("flip H")
            Checkbox(flipV, { flipV = it }); Text("flip V")
        }
        Button({ note = runCatching { snapshot("snap_")?.let { "saved Pictures/StarDeck/$it" } ?: "turn the video on first" }.getOrElse { "save failed: ${it.message}" } }) {
            Text("Save snapshot")
        }

        Text("Shutter", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(shutterMs, { shutterMs = it }, Modifier.weight(1f), singleLine = true, label = { Text("exposure, ms (0.03 to 1000)") })
            Button({
                shutterMs.toDoubleOrNull()?.let {
                    link.send("set_setting", JSONObject().put("manual_exp", true))
                    link.send("set_setting", JSONObject().put("isp_exp_ms", it))
                }
            }) { Text("Set") }
            Button({ link.send("set_setting", JSONObject().put("manual_exp", false)) }) { Text("Auto") }
        }

        Text("Timelapse", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lapseSeconds, { lapseSeconds = it }, Modifier.weight(1f), singleLine = true, label = { Text("every, s") })
            OutlinedTextField(lapseMinutes, { lapseMinutes = it }, Modifier.weight(1f), singleLine = true, label = { Text("for, min (0 = no end)") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ lapseRunning = !lapseRunning }) { Text(if (lapseRunning) "Stop frames" else "Save frames") }
            if (lapseRunning || lapseSaved > 0) Text("$lapseSaved saved")
        }
        // the scope's own timelapse: an mp4 on its storage, no HUD
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val minutes = (lapseMinutes.toDoubleOrNull() ?: 0.0).let { if (it > 0) Math.round(it).toInt() else -1 }
                val every = Math.round(lapseSeconds.toDoubleOrNull() ?: 5.0).toInt().coerceAtLeast(1)
                link.send("start_record_avi", JSONObject().put("raw", false)
                    .put("timelapse", JSONObject().put("enable", true).put("delay_sec", every).put("src_dura_min", minutes)))
            }) { Text("Record on scope") }
            Button({ link.send("stop_record_avi") }) { Text("Stop recording") }
        }
        if (note.isNotEmpty()) Text(note)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ link.send("scope_move_to_horizon") }) { Text("Open") }
            Button({ link.send("scope_park") }) { Text("Park") }
            Checkbox(link.tracking, { link.send("scope_set_track_state", it) })
            Text("tracking")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, mode) in listOf("Star" to "star", "Scenery" to "scenery", "Solar" to "solar_sys")) {
                Button({ link.send("iscope_start_view", JSONObject().put("mode", mode)) }) { Text(label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ link.send("iscope_stop_view", JSONObject()) }) { Text("Stop view") }
            Button({ link.send("scope_move", JSONArray().put("none")) }) { Text("Cancel move") }
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pad("Up", SLEW_UP) { held = it }
            Row(horizontalArrangement = Arrangement.spacedBy(88.dp)) {
                Pad("Left", SLEW_LEFT) { held = it }
                Pad("Right", SLEW_RIGHT) { held = it }
            }
            Pad("Down", SLEW_DOWN) { held = it }
        }
        Text("slew speed ${speed.toInt()} %")
        Slider(speed, { speed = it }, valueRange = 1f..100f)
        Text(link.lastReply)

        Text("Go to object", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("M 31, NGC 7000, Orion Nebula") })
        for (o in catalog.search(query, 6)) {
            Button({
                // the scope expects coordinates of date, the catalogue is J2000
                val (ra, dec) = precessFromJ2000(o.raHours, o.decDeg, julianDateNow())
                link.send("iscope_start_view", JSONObject().put("mode", "star").put("selected_cam", "View")
                    .put("target_name", o.alias.ifEmpty { o.name }).put("target_ra_dec", JSONArray().put(ra).put(dec)).put("lp_filter", false))
                query = ""
            }, Modifier.fillMaxWidth()) { Text("${o.name} ${o.alias}  ${o.kind}  mag ${o.mag.ifEmpty { "?" }}") }
        }

        Text("JSON output", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(telemetry, { telemetry = it; link.telemetryTarget = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("UDP host:port (empty = off)") })

        Text("Settings", style = MaterialTheme.typography.titleMedium)
        for ((key, on) in link.settings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { link.send("set_setting", JSONObject().put(key, it)) })
                Text(key)
            }
        }
        Text("Stack options", style = MaterialTheme.typography.titleMedium)
        for ((key, on) in link.stackSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { link.send("set_setting", JSONObject().put("stack", JSONObject().put(key, it))) })
                Text(key)
            }
        }
        Text("Object catalogue: OpenNGC by Mattia Verga, CC-BY-SA-4.0", style = MaterialTheme.typography.bodySmall)
    }
}

/** A direction button that reports its angle while pressed and null on release. */
@Composable
fun Pad(label: String, angle: Int, onHeld: (Int?) -> Unit) {
    Box(
        Modifier.size(80.dp, 56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
            .pointerInput(angle) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    onHeld(angle)
                    // consume every move so the scrolling column cannot take the gesture over
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    onHeld(null)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label) }
}

@Composable
fun Video(url: String, modifier: Modifier, onView: (PlayerView?) -> Unit) {
    val context = LocalContext.current
    val exo = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) {
        onDispose {
            onView(null)
            exo.release()
        }
    }
    AndroidView(
        factory = { (LayoutInflater.from(it).inflate(R.layout.player, null) as PlayerView).apply { player = exo; onView(this) } },
        modifier = modifier,
    )
}
