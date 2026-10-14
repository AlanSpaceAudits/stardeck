package com.spaceaudits.stardeck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Where photos, videos and time-lapses are kept. Saved between runs by MainActivity. */
object Storage {
    const val BOTH = 0
    const val SEESTAR = 1
    const val PHONE = 2

    var target by mutableStateOf(BOTH)
    /** the Seestar records RAW video instead of mp4 */
    var raw by mutableStateOf(false)

    val onSeestar get() = target != PHONE
    val onPhone get() = target != SEESTAR
}

/** Storage choice: Seestar and phone, Seestar only, or phone only; plus RAW recording on the Seestar. */
@Composable
fun StorageMenu(onChange: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OverlayButton("Storage ▾") { open = true }
        DropdownMenu(open, { open = false }) {
            for ((value, label) in listOf(
                Storage.BOTH to "Seestar and phone",
                Storage.SEESTAR to "Seestar only",
                Storage.PHONE to "Phone only",
            )) {
                DropdownMenuItem(
                    { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(Storage.target == value, null); Text(label) } },
                    { Storage.target = value; onChange() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(Storage.raw, null); Text("RAW video on the Seestar") } },
                { Storage.raw = !Storage.raw; onChange() },
            )
            Text(
                "The Seestar keeps the full-size picture without the HUD. The phone keeps the HUD copy.",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp,
            )
        }
    }
}

/**
 * Box selection for object tracking, laid over the picture. `onBox` gets the box in image pixels
 * (x, y, width, height); `imageSize` is the video size, `flipH`/`flipV` the display flips.
 */
@Composable
fun TrackSelector(imageWidth: Int, imageHeight: Int, flipH: Boolean, flipV: Boolean, onBox: (Int, Int, Int, Int) -> Unit) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0x22000000))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(imageWidth, imageHeight, flipH, flipV) {
                detectDragGestures(
                    onDragStart = { start = it; end = it },
                    onDragEnd = {
                        val a = start
                        val b = end
                        if (a != null && b != null && imageWidth > 0) {
                            fun img(p: Offset): Pair<Double, Double> {
                                var u = p.x / size.width.toDouble()
                                var v = p.y / size.height.toDouble()
                                if (flipH) u = 1 - u
                                if (flipV) v = 1 - v
                                return u * imageWidth to v * imageHeight
                            }
                            val (x1, y1) = img(a)
                            val (x2, y2) = img(b)
                            val w = abs(x2 - x1).roundToInt()
                            val h = abs(y2 - y1).roundToInt()
                            if (w >= 8 && h >= 8) onBox(min(x1, x2).roundToInt(), min(y1, y2).roundToInt(), w, h)
                        }
                        start = null
                        end = null
                    },
                ) { change, d ->
                    change.consume()
                    end = (end ?: Offset.Zero) + d
                }
            },
        ) {
            val a = start
            val b = end
            if (a != null && b != null) {
                drawRect(Color(0xFF4FC3F7), Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)), style = Stroke(4f))
            }
        }
        Text(
            "Draw a box round the object to track",
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(8.dp),
            color = Color.White, fontSize = 13.sp,
        )
    }
}

/** A floating card for a panel shown over the picture. */
@Composable
fun FloatingCard(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Color(0xE6101418), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
        }
        content()
    }
}
