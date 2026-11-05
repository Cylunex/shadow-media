@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.shadow.media

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Reading stays one tap away; secondary actions do not consume a row below every book. */
@Composable internal fun NovelShelfItem(
    work: JSONObject, grid: Boolean, enabled: Boolean, onDetail: () -> Unit, onRead: (() -> Unit)?,
    onManage: (() -> Unit)? = null, onUpdates: (() -> Unit)? = null,
    showReadingInfo: Boolean = false, showActions: Boolean = true,
) {
    var menu by remember(work.optString("id")) { mutableStateOf(false) }
    var information by remember(work.optString("id")) { mutableStateOf(false) }
    val title = work.optString("title")
    val updates = work.optInt("update_count")
    val progress = work.optJSONObject("progress")?.optDouble("ratio", 0.0)?.takeIf { it.isFinite() }
    val chapter = work.optString("last_chapter_title")
    val reading = when {
        chapter.isNotBlank() -> "读至 $chapter"
        progress != null -> "本章已读 ${(progress.coerceIn(0.0, 1.0) * 100).toInt()}%"
        !novelCanRead(work) -> "仅资料"
        else -> "未开始阅读"
    }
    val author = listOf(work.optString("author").ifBlank { "作者待补全" }, serialLabel(work.optString("serial_status")))
        .filter(String::isNotBlank).joinToString(" · ")
    val more: @Composable () -> Unit = {
        if (showActions && (onRead != null || onManage != null || showReadingInfo)) Box {
            IconButton(enabled = enabled, onClick = { menu = true }) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                    Icon(Icons.Rounded.MoreVert, "$title · 更多操作", Modifier.padding(3.dp).size(20.dp))
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("详情") }, onClick = { menu = false; onDetail() })
                if (updates > 0 && onUpdates != null) DropdownMenuItem(text = { Text("查看本次更新") }, onClick = { menu = false; onUpdates() })
                if (showReadingInfo) DropdownMenuItem(text = { Text("阅读信息") }, onClick = { menu = false; information = true })
                if (onManage != null) DropdownMenuItem(text = { Text("管理此书") }, onClick = { menu = false; onManage() })
            }
        }
    }
    val cover: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            NovelBookCover(work, Modifier.fillMaxWidth(), aspectRatio = if (grid) .75f else .7f, showAuthor = false)
            if (updates > 0) Surface(Modifier.align(Alignment.TopStart).padding(4.dp),
                shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.primary) {
                Text("+$updates", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
            if (grid) Box(Modifier.align(Alignment.BottomEnd)) { more() }
            if (work.optBoolean("pinned")) Icon(Icons.Rounded.PushPin, "已置顶", Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onRead ?: onDetail, onLongClick = onManage),
        colors = CardDefaults.cardColors(containerColor = if (grid) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (grid) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cover(Modifier.fillMaxWidth())
            Text(title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reading, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            cover(Modifier.width(52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(reading, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            more()
        }
    }
    if (information) AlertDialog(onDismissRequest = { information = false }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reading)
            NovelShelfReadingSummary(work, showLastChapter = false)
        }
    }, confirmButton = { TextButton(onClick = { information = false }) { Text("关闭") } })
}
