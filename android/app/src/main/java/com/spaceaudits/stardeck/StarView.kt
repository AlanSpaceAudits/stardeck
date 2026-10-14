package com.spaceaudits.stardeck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** An image-channel client that follows the scope connection: connects when `link.ip` is known, closes on dispose. */
@Composable
fun rememberImageStream(link: Link, port: Int = 4800): ImageStreamClient {
    val stream = remember(port) { ImageStreamClient(port) }
    val ip = link.ip
    LaunchedEffect(stream, ip) { if (ip != null) stream.connect(ip) else stream.close() }
    DisposableEffect(stream) { onDispose { stream.close() } }
    return stream
}

/** One object the scope found in a stacked image, in that image's pixels. */
class Annotation(val x: Float, val y: Float, val radius: Float, val name: String)

/** Annotations from `get_annotate_result` and the image size they refer to; null when the scope has none (yet). */
fun parseAnnotations(reply: JSONObject?): Pair<Pair<Int, Int>, List<Annotation>>? {
    val r = reply?.takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("result") ?: return null
    val size = r.optJSONArray("image_size")?.takeIf { it.length() >= 2 } ?: return null
    val items = r.optJSONArray("annotations") ?: return null
    val list = (0 until items.length()).mapNotNull { i ->
        val a = items.optJSONObject(i) ?: return@mapNotNull null
        val names = a.optJSONArray("names")?.let { n -> (0 until n.length()).map { n.optString(it) } }.orEmpty().filter { it.isNotBlank() }
        Annotation(a.optDouble("pixelx").toFloat(), a.optDouble("pixely").toFloat(), a.optDouble("radius", 0.0).toFloat(),
            names.joinToString(" / ").ifEmpty { a.optString("name") })
    }
    return (size.optInt(0) to size.optInt(1)) to list
}

/** The newest frame from the image channel, fitted inside `modifier`, with annotation circles and names. */
@Composable
fun StarImage(stream: ImageStreamClient, modifier: Modifier, flipH: Boolean = false, flipV: Boolean = false,
              notes: Pair<Pair<Int, Int>, List<Annotation>>? = null) {
    val frame = stream.frame
    Box(modifier, contentAlignment = Alignment.Center) {
        if (frame != null) {
            val image = remember(frame) { frame.asImageBitmap() }
            Image(image, stream.info, Modifier.matchParentSize().graphicsLayer(scaleX = if (flipH) -1f else 1f, scaleY = if (flipV) -1f else 1f),
                contentScale = ContentScale.Fit)
            val measurer = rememberTextMeasurer()
            if (notes != null) Canvas(Modifier.matchParentSize()) {
                val (iw, ih) = notes.first
                if (iw <= 0 || ih <= 0) return@Canvas
                val k = minOf(size.width / iw, size.height / ih)
                val (ox, oy) = (size.width - iw * k) / 2 to (size.height - ih * k) / 2
                val gold = Color(0xFFFFD54F)
                for (a in notes.second) {
                    val x = ox + (if (flipH) iw - a.x else a.x) * k
                    val y = oy + (if (flipV) ih - a.y else a.y) * k
                    val r = (a.radius * k).coerceAtLeast(6f)
                    drawCircle(gold, r, Offset(x, y), style = Stroke(2f))
                    drawText(measurer, a.name, Offset(x + r * 0.75f, y + r * 0.75f), TextStyle(color = gold, fontSize = 12.sp))
                }
            }
        }
    }
}

private val STACK_EXPOSURES_S = listOf(10, 20, 30, 60)

/** Star-mode controls for the settings sheet: preview, snapshots, stacking, exposure, gain, LP filter, save. */
@Composable
fun StackPanel(link: Link, stream: ImageStreamClient, gps: Gps, hud: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stackState by remember { mutableStateOf("") }
    var stacked by remember { mutableStateOf(0) }
    var dropped by remember { mutableStateOf(0) }
    var exposureMs by remember { mutableStateOf<Int?>(null) }
    var gain by remember { mutableFloatStateOf(0f) }
    var gainRange by remember { mutableStateOf(0f..400f) }
    var gainKnown by remember { mutableStateOf(false) }
    var lpFilter by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var settingsVersion by remember { mutableStateOf(0) }

    fun readSettings() = link.request("get_device_state", JSONObject().put("keys", JSONArray().put("setting"))) { r ->
        val s = r?.optJSONObject("result")?.optJSONObject("setting") ?: return@request
        s.optJSONObject("exp_ms")?.optInt("stack_l", -1)?.takeIf { it > 0 }?.let { exposureMs = it }
        s.optJSONArray("isp_range_gain")?.let { a ->
            if (a.length() == 2 && a.getDouble(1) > a.getDouble(0)) gainRange = a.getDouble(0).toFloat()..a.getDouble(1).toFloat()
        }
        // a negative isp_gain (e.g. -9990) means no gain has been set
        val g = s.optDouble("isp_gain", Double.NaN)
        gainKnown = !g.isNaN() && g >= 0
        if (gainKnown) gain = g.toFloat()
        lpFilter = s.optBoolean("stack_lenhance", lpFilter)
    }

    fun setSetting(params: JSONObject) {
        link.send("set_setting", params)
        settingsVersion++
    }

    LaunchedEffect(link.connected, settingsVersion) { if (link.connected) readSettings() }
    LaunchedEffect(link.connected) {
        while (link.connected) {
            link.request("iscope_get_app_state") { r ->
                val stack = r?.optJSONObject("result")?.optJSONObject("View")?.optJSONObject("Stack")
                stackState = stack?.optString("state").orEmpty()
                stacked = stack?.optInt("stacked_frame") ?: 0
                dropped = stack?.optInt("dropped_frame") ?: 0
            }
            delay(2000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Star image", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(stream.streaming, { stream.command(if (it) "begin_streaming" else "stop_streaming") })
            Text("live preview")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ stream.command("get_current_img") }) { Text("Snapshot") }
            Button({ stream.command("get_stacked_img") }) { Text("Stacked image") }
        }
        Text("${stream.status}${if (stream.info.isNotEmpty()) "  |  last: ${stream.info}" else ""}", style = MaterialTheme.typography.bodySmall)

        Text("Stack", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ link.send("iscope_start_stack", JSONObject().put("restart", true)) }) { Text("Start stack") }
            Button({ link.send("iscope_stop_view", JSONObject().put("stage", "Stack")) }) { Text("Stop stack") }
        }
        Text(
            if (stackState.isEmpty()) "not stacking" else "$stackState: $stacked frames stacked, $dropped dropped",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Stack exposure", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in STACK_EXPOSURES_S) {
                FilterChip(exposureMs == s * 1000, { setSetting(JSONObject().put("exp_ms", JSONObject().put("stack_l", s * 1000))) }, { Text("$s s") })
            }
        }

        Text("Gain ${if (gainKnown) "%.0f".format(gain) else "--"}", style = MaterialTheme.typography.titleMedium)
        Slider(
            gain.coerceIn(gainRange),
            { gain = it; gainKnown = true },
            valueRange = gainRange,
            onValueChangeFinished = { setSetting(JSONObject().put("isp_gain", Math.round(gain).toDouble())) },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(lpFilter, { lpFilter = it; setSetting(JSONObject().put("stack_lenhance", it)) })
            Text("LP filter")
        }

        Button({
            val frame = stream.frame ?: run { note = "no image to save"; return@Button }
            val lines = if (hud) hudLines(link.pointing, gps) else null
            scope.launch {
                note = runCatching {
                    withContext(Dispatchers.IO) { "saved " + savePng(context, burnHud(frame, false, false, lines), gps.utcMillis()).name }
                }.getOrElse { "save failed: ${it.message}" }
            }
        }, enabled = stream.frame != null) { Text(if (hud) "Save image with HUD" else "Save image") }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}
