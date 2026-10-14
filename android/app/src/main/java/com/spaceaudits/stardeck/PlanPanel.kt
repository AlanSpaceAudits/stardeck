package com.spaceaudits.stardeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Mosaic framing: scale 1.0 to 2.0 times the field, angle in degrees. Scale 1 and angle 0 is no mosaic. */
data class Mosaic(val scale: Double = 1.0, val angle: Double = 0.0, val starMapAngle: Double = 0.0) {
    val isMosaic get() = scale != 1.0 || angle != 0.0

    fun toJson(): JSONObject = JSONObject().put("scale", scale).put("angle", angle).put("star_map_angle", starMapAngle)

    companion object {
        fun fromJson(o: JSONObject?): Mosaic? = o?.let { Mosaic(it.optDouble("scale", 1.0), it.optDouble("angle", 0.0), it.optDouble("star_map_angle", 0.0)) }
    }
}

/** One plan target. RA hours and Dec degrees are J2000. startMin counts from local midnight of the plan day. */
data class PlanItem(
    val targetId: Long,
    val name: String,
    val alias: String,
    val raHours: Double,
    val decDeg: Double,
    val startMin: Int,
    val durationMin: Int,
    val lpFilter: Boolean = false,
    val mosaic: Mosaic? = null,
    val state: String = "",
    val code: Int = 0,
    val stackTotalSec: Double? = null,
)

/** A plan as the scope reports it in `ViewPlan`. */
data class ScopePlan(val name: String, val updated: String, val items: List<PlanItem>, val state: String, val lapseMs: Long)

/** Times before noon belong to the night after midnight, so they sort after the evening ones. */
fun planStartMin(hour: Int, minute: Int): Int = hour * 60 + minute + if (hour < 12) 1440 else 0

fun formatStartMin(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)

/** Parses "HH:MM" into a start minute, or null. */
fun parseStartMin(text: String): Int? {
    val p = text.trim().split(':')
    if (p.size != 2) return null
    val h = p[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val m = p[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return planStartMin(h, m)
}

fun newTargetId(nowMillis: Long, index: Int): Long = (nowMillis % 1_000_000) * 1000 + index

/** Params of `set_view_plan`: items sorted by start, mosaic left out when it is 1x at 0°. */
fun planParams(name: String, updateDate: Date, items: List<PlanItem>): JSONObject {
    val list = JSONArray()
    for (t in items.sortedBy { it.startMin }) {
        val o = JSONObject()
            .put("lp_filter", t.lpFilter)
            .put("target_id", t.targetId)
            .put("target_ra_dec", JSONArray().put(t.raHours).put(t.decDeg))
            .put("target_name", t.name)
            .put("alias_name", t.alias)
            .put("start_min", t.startMin)
            .put("duration_min", t.durationMin)
        t.mosaic?.takeIf { it.isMosaic }?.let { o.put("mosaic", it.toJson()) }
        list.put(o)
    }
    return JSONObject()
        .put("plan_name", name)
        .put("update_time_seestar", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(updateDate))
        .put("list", list)
}

/** Reads the `ViewPlan` object of `iscope_get_app_state` (or a ViewPlan event). */
fun parseViewPlan(o: JSONObject?): ScopePlan? {
    if (o == null) return null
    val plan = o.optJSONObject("plan")
    val list = plan?.optJSONArray("list") ?: JSONArray()
    val items = (0 until list.length()).mapNotNull { i ->
        val t = list.optJSONObject(i) ?: return@mapNotNull null
        val rd = t.optJSONArray("target_ra_dec")
        PlanItem(
            targetId = t.optLong("target_id"),
            name = t.optString("target_name"),
            alias = t.optString("alias_name"),
            raHours = rd?.optDouble(0) ?: Double.NaN,
            decDeg = rd?.optDouble(1) ?: Double.NaN,
            startMin = t.optInt("start_min"),
            durationMin = t.optInt("duration_min"),
            lpFilter = t.optBoolean("lp_filter"),
            mosaic = Mosaic.fromJson(t.optJSONObject("mosaic")),
            state = t.optString("state"),
            code = t.optInt("code"),
            stackTotalSec = t.optDouble("stack_total_sec").takeUnless { it.isNaN() },
        )
    }
    return ScopePlan(plan?.optString("plan_name").orEmpty(), plan?.optString("update_time_seestar").orEmpty(), items, o.optString("state"), o.optLong("lapse_ms"))
}

private fun replyText(method: String, r: JSONObject?) = when {
    r == null -> "$method: no reply"
    r.optInt("code") != 0 -> "$method: ${r.optString("error").ifEmpty { "code ${r.optInt("code")}" }}"
    else -> "$method: ok"
}

/** Observation plan: build a target list, send it to the scope, start, stop, and follow it. */
@Composable
fun PlanPanel(link: Link, catalog: Catalog) {
    var name by remember { mutableStateOf("StarDeck plan") }
    val items = remember { mutableStateListOf<PlanItem>() }
    var query by remember { mutableStateOf("") }
    var stored by remember { mutableStateOf<ScopePlan?>(null) }
    var reply by remember { mutableStateOf("") }
    val goHome = link.setting?.takeIf { it.has("viewplan_gohome") }?.optBoolean("viewplan_gohome")

    LaunchedEffect(link.connected) {
        while (link.connected) {
            link.request("iscope_get_app_state") { r ->
                r?.optJSONObject("result")?.let { stored = parseViewPlan(it.optJSONObject("ViewPlan")) }
            }
            delay(3000)
        }
    }

    fun call(method: String, params: Any? = null) = link.request(method, params) { reply = replyText(method, it) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Plan on the scope", style = MaterialTheme.typography.titleMedium)
        val s = stored
        if (s == null || s.items.isEmpty()) Text("no plan stored", style = MaterialTheme.typography.bodySmall)
        else {
            Text(
                "${s.name}  ${s.updated}  ${s.state.ifEmpty { "idle" }}" + if (s.lapseMs > 0) "  ${s.lapseMs / 60000} min" else "",
                fontFamily = FontFamily.Monospace,
            )
            for (t in s.items.sortedBy { it.startMin }) {
                Text(
                    "${formatStartMin(t.startMin)} ${t.durationMin}m  ${t.name}  ${t.state.ifEmpty { "-" }}" +
                        (if (t.code != 0) " code ${t.code}" else "") +
                        (t.stackTotalSec?.let { "  %.0f s stacked".format(it) } ?: "") +
                        (if (t.lpFilter) "  LP" else "") +
                        (t.mosaic?.takeIf { it.isMosaic }?.let { "  mosaic %.1fx %.0f°".format(it.scale, it.angle) } ?: ""),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // set_view_plan without params runs the stored plan again
            Button({ call("set_view_plan") }, enabled = link.connected && s != null && s.items.isNotEmpty()) { Text("Restart") }
            Button({ call("stop_func", JSONObject().put("name", "ViewPlan")) }, enabled = link.connected) { Text("Stop") }
            OutlinedButton({ call("clear_view_plan") }, enabled = link.connected) { Text("Delete") }
        }
        OutlinedButton({
            s?.let {
                name = it.name.ifEmpty { name }
                items.clear()
                items.addAll(it.items.map { t -> t.copy(state = "", code = 0, stackTotalSec = null) })
            }
        }, enabled = s != null && s.items.isNotEmpty()) { Text("Copy into the editor") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(goHome == true, { link.send("set_setting", JSONObject().put("viewplan_gohome", it)) }, enabled = link.connected && goHome != null)
            Text("park the scope when the plan ends")
        }

        HorizontalDivider()
        Text("New plan", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("plan name") })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("add target: M 31, NGC 7000") })
        for (o in catalog.search(query, 5)) {
            Button({
                val start = items.maxOfOrNull { it.startMin + it.durationMin } ?: Calendar.getInstance().let { planStartMin(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
                items.add(PlanItem(newTargetId(System.currentTimeMillis(), items.size), o.alias.ifEmpty { o.name }, o.name, o.raHours, o.decDeg, start, 60))
                query = ""
            }, Modifier.fillMaxWidth()) { Text("${o.name} ${o.alias}  ${o.kind}  mag ${o.mag.ifEmpty { "?" }}") }
        }
        for ((i, t) in items.withIndex()) PlanItemEditor(t, { items[i] = it }, { items.removeAt(i) })

        val overlap = items.sortedBy { it.startMin }.zipWithNext().any { (a, b) -> a.startMin + a.durationMin > b.startMin }
        if (overlap) Text("targets overlap in time", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button({
            call("set_view_plan", planParams(name, Date(), items.toList()))
        }, enabled = link.connected && items.isNotEmpty() && name.isNotBlank()) { Text("Send to the scope and start") }
        if (reply.isNotEmpty()) Text(reply, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlanItemEditor(t: PlanItem, onChange: (PlanItem) -> Unit, onRemove: () -> Unit) {
    var start by remember(t.targetId) { mutableStateOf(formatStartMin(t.startMin)) }
    var duration by remember(t.targetId) { mutableStateOf(t.durationMin.toString()) }
    var scale by remember(t.targetId) { mutableStateOf(String.format(Locale.US, "%.1f", t.mosaic?.scale ?: 1.0)) }
    var angle by remember(t.targetId) { mutableStateOf(String.format(Locale.US, "%.0f", t.mosaic?.angle ?: 0.0)) }
    fun mosaicOf(sc: String, an: String) = Mosaic(sc.toDoubleOrNull()?.coerceIn(1.0, 2.0) ?: 1.0, an.toDoubleOrNull()?.coerceIn(-90.0, 90.0) ?: 0.0)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${t.name}  ${t.alias}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onRemove) { Text("Remove") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(start, { v -> start = v; parseStartMin(v)?.let { onChange(t.copy(startMin = it)) } }, Modifier.weight(1f), singleLine = true, label = { Text("start HH:MM") })
            OutlinedTextField(duration, { v -> duration = v; v.toIntOrNull()?.takeIf { it > 0 }?.let { onChange(t.copy(durationMin = it)) } }, Modifier.weight(1f), singleLine = true, label = { Text("minutes") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(scale, { v -> scale = v; onChange(t.copy(mosaic = mosaicOf(v, angle))) }, Modifier.width(110.dp), singleLine = true, label = { Text("mosaic 1-2x") })
            OutlinedTextField(angle, { v -> angle = v; onChange(t.copy(mosaic = mosaicOf(scale, v))) }, Modifier.width(110.dp), singleLine = true, label = { Text("angle °") })
            Checkbox(t.lpFilter, { onChange(t.copy(lpFilter = it)) })
            Text("LP")
        }
    }
}
