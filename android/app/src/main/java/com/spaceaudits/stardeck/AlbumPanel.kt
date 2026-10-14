package com.spaceaudits.stardeck

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** The scope's album: folders, then files with thumbnail, type, date and size; download to Download/StarDeck. */
@Composable
fun AlbumPanel(link: Link) {
    val context = LocalContext.current
    var dir by remember { mutableStateOf("") } // "" = album root
    var items by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loadCount by remember { mutableIntStateOf(0) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    val sizes = remember { mutableStateMapOf<String, Long>() }
    var selected by remember { mutableStateOf<AlbumItem?>(null) }
    var progress by remember { mutableStateOf("") }
    // thumbnails and sizes load one at a time so they do not compete with a download
    val loader = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { loader.shutdownNow() } }

    LaunchedEffect(dir, loadCount, link.connected) {
        if (!link.connected) return@LaunchedEffect
        status = "loading…"
        val params = if (dir.isEmpty()) null else JSONObject().put("name", dir)
        val wanted = dir
        link.request("get_albums", params) { reply ->
            if (wanted != dir) return@request
            if (reply == null || reply.optInt("code", -1) != 0) {
                status = "get_albums: ${reply?.optString("error")?.ifEmpty { null } ?: "code ${reply?.optInt("code") ?: "no reply"}"}"
                return@request
            }
            val list = parseAlbums(reply.optJSONObject("result"))
            items = list
            status = if (list.isEmpty()) "empty" else ""
            val ip = link.ip ?: return@request
            for (item in list) {
                // the loader is shut down once the panel closes
                runCatching {
                    loader.execute {
                        if (wanted != dir) return@execute
                        if (!item.isFolder && item.path !in sizes) sizes[item.path] = albumSize(ip, item.path)
                        if (item.thumbPath.isNotEmpty() && item.thumbPath !in thumbs) {
                            runCatching { albumBytes(ip, item.thumbPath) }.getOrNull()
                                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                ?.let { thumbs[item.thumbPath] = it.asImageBitmap() }
                        }
                    }
                }
            }
        }
    }

    fun download(item: AlbumItem, asJpg: Boolean) {
        val ip = link.ip ?: return
        progress = "starting ${item.name}"
        thread {
            progress = runCatching {
                val saved = if (asJpg) {
                    val bytes = fetchOnFilePort(ip, "get_img_file_in_jpg", item.path)
                    saveDownload(context, item.name.substringBeforeLast('.') + ".jpg", ByteArrayInputStream(bytes), bytes.size.toLong()) { _, _ -> }
                } else {
                    downloadAlbumFile(context, ip, item) { done, total ->
                        progress = "${item.name}  ${formatBytes(done)} of ${formatBytes(total)}"
                    }
                }
                "saved Download/StarDeck/$saved"
            }.getOrElse { "download failed: ${it.message}" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dir.isNotEmpty()) OutlinedButton({ dir = dir.substringBeforeLast('/', "").let { if (it == "MyWorks") "" else it }; selected = null }) { Text("Back") }
            OutlinedButton({ loadCount++ }, enabled = link.connected) { Text("Refresh") }
            Text(dir.ifEmpty { "Album" }, style = MaterialTheme.typography.bodySmall)
        }
        if (!link.connected) Text("not connected")
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (progress.isNotEmpty()) Text(progress, style = MaterialTheme.typography.bodySmall)

        var lastGroup: String? = null
        for (item in items) {
            if (item.group != lastGroup) {
                lastGroup = item.group
                Text(item.group, style = MaterialTheme.typography.titleSmall)
            }
            val isSelected = selected == item
            Row(
                Modifier.fillMaxWidth()
                    .background(if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable { if (item.isFolder) { dir = item.path; selected = null } else selected = if (isSelected) null else item },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                    val t = thumbs[item.thumbPath]
                    if (t != null) Image(t, item.name, Modifier.size(48.dp), contentScale = ContentScale.Crop)
                    else if (item.isFolder) Text("▤")
                }
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1)
                    val details = listOfNotNull(
                        albumTypeLabel(item.type),
                        item.count?.let { "$it items" },
                        item.date.ifEmpty { null },
                        item.durationSec?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) },
                        sizes[item.path]?.takeIf { it >= 0 }?.let { formatBytes(it) },
                    )
                    Text(details.joinToString("  "), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isSelected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ download(item, asJpg = false) }) { Text("Download") }
                    if (item.isFits) Button({ download(item, asJpg = true) }) { Text("Download as JPG") }
                }
            }
        }
    }
}
