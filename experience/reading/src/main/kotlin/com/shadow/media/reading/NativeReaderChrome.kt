package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.media.database.ContentAnnotationEntity
import com.shadow.media.library.LibraryRepository

@Composable
internal fun NativeReaderHeader(title: String, chapter: String, status: String?, enabled: Boolean, onBack: () -> Unit, onClose: () -> Unit, onBookmark: () -> Unit, conflict: (@Composable () -> Unit)? = null, night: Boolean = false, onToggleNight: (() -> Unit)? = null) {
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "退出阅读") }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(chapter, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(enabled = enabled, onClick = onBookmark) { Icon(Icons.Rounded.BookmarkAdd, "添加书签") }
                onToggleNight?.let { toggle ->
                    IconButton(onClick = toggle) { Icon(if (night) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (night) "恢复日间纸张" else "切换夜间阅读") }
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "收起阅读菜单") }
            }
            status?.let { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            conflict?.invoke()
        }
    }
}

@Composable
internal fun NativeReaderControls(
    chapter: String, pageLabel: String, progress: Float, enabled: Boolean, narrating: Boolean,
    canReturn: Boolean, onReturn: () -> Unit, onSeek: (Float) -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onSpeak: () -> Unit, onPanel: (String) -> Unit, compact: Boolean = false, sources: Boolean = false,
) {
    Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = if (compact) 4.dp else 16.dp, bottom = 4.dp)) {
            if (canReturn && !compact) TextButton(enabled = enabled, onClick = onReturn, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("返回跳转前的位置")
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canReturn) IconButton(enabled = enabled, onClick = onReturn) { Icon(Icons.AutoMirrored.Rounded.Undo, "返回跳转前的位置") }
                IconButton(enabled = enabled, onClick = onPrevious) { Icon(Icons.Rounded.ChevronLeft, "上一页") }
                ReaderProgressControl(progress, enabled, "本章", compact = true, modifier = Modifier.weight(1f), seek = onSeek)
                IconButton(enabled = enabled, onClick = onNext) { Icon(Icons.Rounded.ChevronRight, "下一页") }
                IconButton(enabled = enabled, onClick = onSpeak) { Icon(if (narrating) Icons.Rounded.Stop else Icons.Rounded.Headphones, if (narrating) "停止朗读" else "朗读") }
            } else {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chapter, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Text(pageLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ReaderProgressControl(progress, enabled, "本章", seek = onSeek)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = enabled, onClick = onPrevious, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.ChevronLeft, null); Text("上一页") }
                    FilledTonalButton(enabled = enabled, onClick = onSpeak) {
                        Icon(if (narrating) Icons.Rounded.Stop else Icons.Rounded.Headphones, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (narrating) "停止朗读" else "朗读")
                    }
                    TextButton(enabled = enabled, onClick = onNext, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("下一页"); Icon(Icons.Rounded.ChevronRight, null) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                (listOf("目录" to Icons.AutoMirrored.Rounded.MenuBook, "排版" to Icons.Rounded.TextFields,
                    "搜索" to Icons.Rounded.Search, "书签" to Icons.Rounded.Bookmarks) +
                    if (sources) listOf("书源" to Icons.Rounded.SwapHoriz) else emptyList()).forEach { (label, icon) ->
                    ReaderTool(label, icon, enabled, Modifier.weight(1f)) { onPanel(label) }
                }
            }
        }
    }
}

@Composable private fun ReaderTool(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
        Column(Modifier.heightIn(min = 64.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable internal fun ReaderEmptyState(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ColumnScope.NativeReaderBookmarks(marks: List<ContentAnnotationEntity>, enabled: Boolean, onOpen: (ContentAnnotationEntity) -> Unit, onDelete: (ContentAnnotationEntity) -> Unit) {
    if (marks.isEmpty()) ReaderEmptyState(Icons.Rounded.Bookmarks, "还没有书签", "阅读菜单右上角可添加书签，长按正文可保存摘录。", Modifier.weight(1f))
    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(marks, key = { it.id }) { mark ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = { onOpen(mark) }, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(mark.note.ifBlank { "书签" }, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("点击回到原文", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(enabled = enabled, onClick = { onDelete(mark) }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") }
                }
            }
        }
    }
}

internal suspend fun deleteReaderBookmark(library: LibraryRepository, mark: ContentAnnotationEntity, snackbar: SnackbarHostState) {
    library.dao.removeAnnotation(mark.id)
    if (snackbar.showSnackbar("已删除书签", actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
        library.dao.putAnnotation(mark)
}
