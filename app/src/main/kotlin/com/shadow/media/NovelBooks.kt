package com.shadow.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.shadow.media.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class NovelArtwork(val repository: NovelRepository, val connection: CatalogConnection)
internal val LocalNovelArtwork = staticCompositionLocalOf<NovelArtwork?> { null }

/** Covers use the same authenticated API and origin checks as book data; no arbitrary image URL is opened. */
@Composable internal fun NovelBookCover(work: JSONObject, modifier: Modifier = Modifier) {
    val artwork = LocalNovelArtwork.current
    val id = work.optString("id")
    val revision = work.optString("revision")
    val hasCover = !work.isNull("cover_url") && work.optString("cover_url").isNotBlank()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, artwork?.connection?.id, id, revision, hasCover) {
        value = null
        if (artwork != null && id.isNotBlank() && hasCover) {
            val bytes = artwork.repository.cover(artwork.connection, id, revision)
            value = bytes?.let { withContext(Dispatchers.Default) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) null else {
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                    BitmapFactory.decodeByteArray(it, 0, it.size, options)?.asImageBitmap()
                }
            } }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val (paper, ink) = when ((work.optString("title").hashCode() and Int.MAX_VALUE) % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Box(modifier.aspectRatio(0.7f).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(
        listOf(paper, paper.copy(alpha = .78f)))), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Box(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxHeight().background(ink.copy(alpha = .10f)))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(work.optString("title").take(18), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium, color = ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (work.optString("author").isNotBlank()) Text(work.optString("author"), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, color = ink.copy(alpha = .8f), overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun NovelWorkCard(work: JSONObject, enabled: Boolean, onClick: () -> Unit) {
    Card(enabled = enabled, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NovelBookCover(work, Modifier.width(68.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(work.optString("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(work.optString("author").ifBlank { "作者待补全" }, work.optString("category"), serialLabel(work.optString("serial_status")))
                    .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (work.optString("description").isNotBlank()) Text(work.optString("description"), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tags = work.novelStrings("genre_tags").take(3).joinToString(" · ")
                if (tags.isNotBlank()) Text(tags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (work.optInt("update_count") > 0) Text("${work.optInt("update_count")} 章更新", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (work.optBoolean("on_shelf")) Text("已加入书架", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable internal fun NovelBookStrip(works: List<JSONObject>, enabled: Boolean, onWork: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(works, key = { it.getString("id") }) { work ->
            Card(enabled = enabled, onClick = { onWork(work.getString("id")) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.width(108.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NovelBookCover(work, Modifier.fillMaxWidth())
                    Text(work.optString("title"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(work.optString("author").ifBlank { "作者待补全" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable internal fun NovelBooklistCard(list: JSONObject, enabled: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Card(enabled = enabled, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.novelObjects("preview").take(3).forEach { NovelBookCover(it, Modifier.width(58.dp)) }
                Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(list.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${list.optInt("count")} 本书", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(list.optString("description").ifBlank { "把值得读的故事放在一起，留下自己的推荐语。" }, maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing()
        }
    }
}

internal fun serialLabel(value: String) = when (value) { "completed" -> "完结"; "ongoing" -> "连载"; "hiatus" -> "停更"; else -> "" }

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NovelWorkOverview(work: JSONObject, enabled: Boolean, canSearch: Boolean, onRead: () -> Unit, onAuthor: () -> Unit, onSearch: () -> Unit) {
    val readable = novelCanRead(work) && work.novelObjects("versions").any { !it.isNull("id") && it.optString("id").isNotBlank() }
    var expanded by rememberSaveable(work.optString("id")) { mutableStateOf(false) }
    var overflowing by remember(work.optString("description")) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NovelBookCover(work, Modifier.width(96.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(work.optString("title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (!work.optBoolean("author_unknown") && work.optString("author").isNotBlank()) TextButton(enabled = enabled, onClick = onAuthor, contentPadding = PaddingValues(0.dp)) { Text("${work.optString("author")} ›") }
                else Text("作者待补全", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (readable) "可阅读" else "资料已收录 · 暂无正文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(listOf(work.optString("category"), serialLabel(work.optString("serial_status"))).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = enabled && readable, onClick = onRead, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (!readable) "暂无可读版本" else if (work.optJSONObject("progress") != null) "继续阅读" else "开始阅读")
            }
            FilledTonalButton(enabled = enabled && canSearch, onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("找书源")
            }
        }
        work.optJSONObject("reading_summary")?.let {
            Text("${it.optInt("versions")} 个版本 · 最多 ${it.optInt("chapters")} 章 · 本地约 ${it.optLong("local_characters")} 字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (work.novelStrings("genre_tags").isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            work.novelStrings("genre_tags").take(8).forEach { tag ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(tag, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("作品简介", style = MaterialTheme.typography.titleSmall)
                Text(work.optString("description").ifBlank { "暂无简介" }, maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow }, style = MaterialTheme.typography.bodyMedium)
                if (overflowing || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起简介" else "展开简介") }
                if (work.optString("aliases").isNotBlank()) Text("别名：${work.optString("aliases")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        work.optJSONObject("popularity")?.let { popularity ->
            Text("热度 ${work.opt("popularity_score")} · ${popularity.optString("source")}\n原始${popularity.optString("metric")}：${popularity.optString("raw").ifBlank { popularity.opt("value")?.toString().orEmpty() }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (popularity.optDouble("observed_at", 0.0) > 0) Text("观测于 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date((popularity.optDouble("observed_at") * 1000).toLong()))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
