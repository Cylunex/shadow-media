package com.shadow.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    Box(modifier.aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primaryContainer))), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(work.optString("title").take(14), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (work.optString("author").isNotBlank()) Text(work.optString("author"), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, color = MaterialTheme.colorScheme.onPrimaryContainer, overflow = TextOverflow.Ellipsis)
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
