package com.shadow.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.shadow.media.library.novelObjects
import com.shadow.media.library.novelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun novelNextOfflineStatus(directory: JSONObject?, chapter: String, cached: Set<String>): String {
    if (directory == null) return "离线目录待缓存"
    val rows = directory.novelObjects("items")
    val index = rows.indexOfFirst { it.optString("id") == chapter }
    if (index < 0) return "离线位置待确认"
    val next = rows.drop(index + 1).firstOrNull { it.optBoolean("readable", true) }
        ?: return "已到目录末章"
    return if (next.optString("id") in cached) "下一章已缓存" else "下一章未缓存"
}

internal fun novelContinuousOfflineCount(directory: JSONObject?, current: String?, cached: Set<String>): Int? {
    val rows = directory?.novelObjects("items")?.filter { it.optBoolean("readable", true) } ?: return null
    val index = if (current == null) 0 else rows.indexOfFirst { it.optString("id") == current }
    if (index < 0) return null
    return rows.drop(index).takeWhile { it.optString("id") in cached }.size
}

@Composable internal fun NovelShelfReadingSummary(work: JSONObject, showLastChapter: Boolean) {
    val artwork = LocalNovelArtwork.current
    val progress = work.optJSONObject("progress")
    var offline by remember(work.optString("id")) { mutableStateOf<String?>(null) }
    if (artwork != null) {
        val changes by artwork.repository.cache.changes.collectAsState()
        val workId = work.optString("id")
        LaunchedEffect(workId, progress?.toString(), changes) {
            try {
                offline = withContext(Dispatchers.IO) {
                    val scope = novelScope(artwork.connection)
                    val state = artwork.repository.dao.state(com.shadow.media.library.NovelRepository.stateId(scope, workId))
                    val local = state?.localPosition?.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val position = (if (state?.pending == true) local else progress) ?: local ?: return@withContext null
                    val version = position.optString("version_id")
                    val chapter = position.optString("chapter_id")
                    novelNextOfflineStatus(artwork.repository.cache.savedDirectory(scope, workId, version), chapter,
                        artwork.repository.cache.chapters(scope, workId, version).map { it.getString("id") }.toSet())
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { offline = "离线状态待确认" }
        }
    }
    if (showLastChapter && work.optString("last_chapter_title").isNotBlank()) Text("读至 " + work.optString("last_chapter_title"), maxLines = 1, style = MaterialTheme.typography.labelSmall)
    if (work.optString("latest_chapter_title").isNotBlank()) Text("最新 " + work.optString("latest_chapter_title"), maxLines = 1, style = MaterialTheme.typography.labelSmall)
    val updated = work.optDouble("catalog_updated_at", 0.0)
    if (updated.isFinite() && updated > 0) Text("目录更新 " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date((updated * 1000).toLong())), style = MaterialTheme.typography.labelSmall)
    offline?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
}
