@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.shadow.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shadow.media.audio.MediaOfflineStore
import com.shadow.media.audio.localAudioUri
import com.shadow.media.database.CommuteAudioRow
import com.shadow.media.database.LibraryAssetEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File

internal data class CommuteChoice(val row: CommuteAudioRow, val position: Long, val remaining: Long?) {
    fun group(minutes: Int): Int = when {
        remaining == null -> 2
        remaining > minutes * 60_000L -> 1
        else -> 0
    }
}

internal fun CommuteAudioRow.choice(): CommuteChoice? {
    val locator = locatorJson?.let { runCatching { JSONObject(it) }.getOrNull() }
    val position = if (asset.kind == "MUSIC") 0L else locator?.optLong("positionMs", 0)?.coerceAtLeast(0) ?: 0L
    val duration = locator?.optLong("durationMs", 0)?.takeIf { it > 0 } ?: durationMs?.takeIf { it > 0 }
    if (duration != null && duration <= position) return null
    return CommuteChoice(this, position, duration?.minus(position))
}

private suspend fun CommuteAudioRow.hasOfflineBytes(context: Context): Boolean {
    if (downloaded && MediaOfflineStore.get(context).available(asset)) return true
    val uri = Uri.parse(asset.localUri)
    if (asset.providerId != "local" || !localAudioUri(uri)) return false
    return try {
        if (uri.scheme == "file") File(uri.path.orEmpty()).let { it.isFile && it.canRead() && it.length() > 0 }
        else context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0 } == true
    } catch (_: java.io.IOException) { false } catch (_: SecurityException) { false }
}

internal suspend fun commuteChoices(rows: List<CommuteAudioRow>, minutes: Int, available: suspend (CommuteAudioRow) -> Boolean): List<CommuteChoice> {
    val selected = ArrayList<CommuteChoice>()
    val counts = IntArray(3)
    // DAO orders recent progress first. Within each group prefer an episode already started.
    for (choice in rows.mapNotNull { it.choice() }.sortedByDescending { it.position > 0 }) {
        val group = choice.group(minutes)
        if (counts[group] < 3 && available(choice.row)) {
            selected += choice
            counts[group]++
        }
        if (counts.all { it == 3 }) break
    }
    return selected.sortedBy { it.group(minutes) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable internal fun CommuteScreen(container: AppContainer, state: MainUiState, minutes: Int,
    onMinutes: (Int) -> Unit, onBack: () -> Unit, onPlay: (LibraryAssetEntity) -> Unit) {
    val context = LocalContext.current.applicationContext
    val providers by rememberContinueProviders(container, state).collectAsStateWithLifecycle(emptyList())
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row { TextButton(onClick = onBack) { Text("返回") }; Text("通勤听 · 已下载", style = MaterialTheme.typography.titleLarge) }
        Text("这次有 ${minutes} 分钟 · 按原速估算")
        Row { listOf(10, 20, 30, 60).forEach { m -> TextButton(onClick = { onMinutes(m) }) { Text("${m} 分") } } }
        key(providers, minutes) {
            var error by remember { mutableStateOf(false) }
            var retry by remember { mutableIntStateOf(0) }
            val results by remember(providers, minutes, retry) {
                container.library.dao.commuteAudio(providers).distinctUntilChanged().mapLatest { rows ->
                    commuteChoices(rows, minutes) { it.hasOfflineBytes(context) }
                }.flowOn(Dispatchers.IO).catch { error = true }
            }.collectAsStateWithLifecycle(null)
            when {
                error -> TextButton(onClick = { error = false; retry++ }) { Text("本机书库读取失败，重试") }
                results == null -> LinearProgressIndicator()
                results!!.isEmpty() -> Text("当前来源没有可用的离线音频。先在歌曲或听书选项中保存离线副本。")
                else -> LazyColumn {
                    results!!.groupBy { it.group(minutes) }.forEach { (group, choices) ->
                        item { Text(listOf("这段时间能听完", "可以先听一段", "时长未知")[group], style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                        items(choices, key = { it.row.asset.id }) { choice ->
                            val asset = choice.row.asset
                            ListItem(headlineContent = { Text(asset.title) }, supportingContent = {
                                val from = if (choice.position > 0) "从 %d:%02d 继续".format(choice.position / 60_000, choice.position / 1000 % 60) else "从头听"
                                Text(from + (choice.remaining?.let { " · 剩余约 ${(it - 1) / 60_000 + 1} 分钟" } ?: ""))
                            }, trailingContent = { TextButton(onClick = { onPlay(asset) }) { Text("播放") } })
                        }
                    }
                }
            }
        }
    }
}
