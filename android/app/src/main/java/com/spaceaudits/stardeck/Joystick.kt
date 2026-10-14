package com.spaceaudits.stardeck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Slew speed in percent from the stick deflection in percent (0 to 100), as the official app maps it:
 * a fine range up to 40 % for the inner 40 % of travel, then a steeper rise to full speed.
 */
fun stickPercent(deflection: Double): Int = when {
    deflection < 1 -> 0
    deflection <= 5 -> 1
    deflection <= 40 -> 1 + ((deflection - 5) * 0.97).toInt()
    deflection <= 90 -> 40 + ((deflection - 40) * 1.2).toInt()
    else -> 100
}

/** Stick direction in scope_speed_move degrees: 0 = right (azimuth +), 90 = up (elevation +). */
fun stickAngle(dx: Float, dyDown: Float): Int = ((Math.toDegrees(atan2(-dyDown.toDouble(), dx.toDouble())) + 360.0) % 360.0).toInt()

private val panel = Color(0x8C000000)
private val pillOn = Color(0x66FFFFFF)

/**
 * Joystick panel laid out like the official one: speed pill on top, the stick, tracking pill below.
 * `onMove` gets (angle, percent) while the stick is deflected and null when it is released.
 */
@Composable
fun JoystickPanel(
    fast: Boolean, onFast: (Boolean) -> Unit, tracking: Boolean, onTracking: (Boolean) -> Unit,
    onMove: (Pair<Int, Int>?) -> Unit, modifier: Modifier = Modifier, stick: Dp = 128.dp,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier.width(stick + 8.dp).background(panel, RoundedCornerShape(20.dp)).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.background(Color(0x99000000), RoundedCornerShape(24.dp)).padding(2.dp)) {
            for ((label, value) in listOf("Slow" to false, "Fast" to true)) {
                Box(
                    Modifier.size(48.dp, 22.dp).background(if (fast == value) pillOn else Color.Transparent, RoundedCornerShape(24.dp))
                        .clickable { onFast(value) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 12.sp, color = Color.White) }
            }
        }
        Canvas(
            Modifier.size(stick).pointerInput(Unit) {
                val r = size.width / 2f
                fun update(p: Offset) {
                    var d = Offset(p.x - r, p.y - r)
                    val len = hypot(d.x, d.y)
                    if (len > r) d *= r / len
                    knob = d
                    val percent = stickPercent(min(len / r, 1f) * 100.0)
                    onMove(if (percent == 0) null else stickAngle(d.x, d.y) to percent)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    update(down.position)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        event.changes.firstOrNull { it.pressed }?.let { update(it.position) }
                    } while (event.changes.any { it.pressed })
                    knob = Offset.Zero
                    onMove(null)
                }
            },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.width / 2
            drawCircle(Color(0x33FFFFFF), r, c)
            drawCircle(Color(0x99FFFFFF), r - 2, c, style = Stroke(3f))
            // direction marks
            for (a in 0 until 4) {
                val rad = Math.toRadians(a * 90.0)
                val dir = Offset(kotlin.math.cos(rad).toFloat(), -kotlin.math.sin(rad).toFloat())
                drawLine(Color(0x99FFFFFF), c + dir * (r * 0.78f), c + dir * (r * 0.92f), 4f)
            }
            drawCircle(if (knob == Offset.Zero) Color.White else Color(0xFFFF5A5A), r * 0.311f, c + knob)
        }
        Box(
            Modifier.size(102.dp, 30.dp).background(if (tracking) Color(0xCC2E7D32) else Color(0x99000000), RoundedCornerShape(15.dp))
                .clickable { onTracking(!tracking) },
            contentAlignment = Alignment.Center,
        ) { Text(if (tracking) "Tracking on" else "Tracking off", fontSize = 12.sp, color = Color.White) }
    }
}

/** Where the joystick sits, whether it is folded to a button, and its size; kept between runs. */
class JoyLayout(private val prefs: android.content.SharedPreferences) {
    var x by mutableStateOf(prefs.getFloat("joy2_x", -1f))
    var y by mutableStateOf(prefs.getFloat("joy2_y", -1f))
    var collapsed by mutableStateOf(prefs.getBoolean("joy2_collapsed", false))
    var size by mutableStateOf(prefs.getInt("joy2_size", 1)) // 0 small, 1 normal, 2 large

    fun save() = prefs.edit().putFloat("joy2_x", x).putFloat("joy2_y", y).putBoolean("joy2_collapsed", collapsed).putInt("joy2_size", size).apply()
}

private val STICK_SIZES = listOf(96.dp, 128.dp, 168.dp)

/**
 * The joystick panel with a grip bar: drag the bar to move it anywhere on the screen, S/M/L to resize,
 * and – to fold it to a small button (tap the button to unfold, drag it to move).
 */
@Composable
fun MovableJoystick(
    layout: JoyLayout, container: androidx.compose.ui.unit.IntSize,
    fast: Boolean, onFast: (Boolean) -> Unit, tracking: Boolean, onTracking: (Boolean) -> Unit, onMove: (Pair<Int, Int>?) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var own by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // first run: lower left, clear of the capture tabs and the Settings button
    if ((layout.x < 0 || layout.y < 0) && own.height > 0 && container.height > 0) {
        with(density) {
            layout.x = 8.dp.toPx()
            layout.y = container.height - own.height - 210.dp.toPx()
        }
    }
    fun moveBy(dx: Float, dy: Float) {
        layout.x = (layout.x + dx).coerceIn(0f, (container.width - own.width).toFloat().coerceAtLeast(0f))
        layout.y = (layout.y + dy).coerceIn(0f, (container.height - own.height).toFloat().coerceAtLeast(0f))
    }
    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = { layout.save() }) { change, d ->
            change.consume()
            moveBy(d.x, d.y)
        }
    }
    Box(
        Modifier.offset { androidx.compose.ui.unit.IntOffset(layout.x.coerceAtLeast(0f).toInt(), layout.y.coerceAtLeast(0f).toInt()) }
            .onSizeChanged { own = it },
    ) {
        if (layout.collapsed) {
            Box(
                Modifier.size(52.dp).background(panel, androidx.compose.foundation.shape.CircleShape).then(drag)
                    .clickable { layout.collapsed = false; layout.save() },
                contentAlignment = Alignment.Center,
            ) { Text("✥", color = Color.White, fontSize = 24.sp) }
        } else {
            val stick = STICK_SIZES[layout.size.coerceIn(0, 2)]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.width(stick + 8.dp).height(26.dp).background(Color(0xB3000000), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).height(26.dp).then(drag), contentAlignment = Alignment.Center) { Text("⠿ move", color = Color.White, fontSize = 11.sp) }
                    Text(listOf("S", "M", "L")[layout.size.coerceIn(0, 2)], color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clickable { layout.size = (layout.size + 1) % 3; layout.save() }.padding(horizontal = 8.dp))
                    Text("–", color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.clickable { layout.collapsed = true; layout.save() }.padding(horizontal = 8.dp))
                }
                JoystickPanel(fast, onFast, tracking, onTracking, onMove, stick = stick)
            }
        }
    }
}
