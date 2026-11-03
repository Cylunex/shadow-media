package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import com.shadow.media.database.LibraryAssetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class OfflineVersion(val asset: LibraryAssetEntity, val label: String)
private data class OfflineWork(val id: String, val title: String, val versions: List<OfflineVersion>)

@Composable internal fun NovelOfflineLibrary(repository: NovelRepository, scope: String, onOpen: (LibraryAssetEntity) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { LibraryRepository(context) }
    var works by remember(scope) { mutableStateOf<List<OfflineWork>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val updates by repository.cache.changes.collectAsState()
    val tasks by remember(scope) { repository.downloads.observeAll(scope) }.collectAsState(emptyList())
    val coroutine = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope, updates) {
        try {
            val groups = repository.dao.publications(scope).groupBy { it.workId }
            val assets = repository.downloaded(scope)
            works = groups.mapNotNull { (work, publications) ->
                val versions = assets.filter { asset -> publications.any { it.assetId == asset.id } }.map { asset ->
                    val publication = publications.first { it.assetId == asset.id }
                    val chapters = repository.cache.chapters(scope, work, publication.versionId)
                    val saved = repository.dao.state(NovelRepository.stateId(scope, work))?.localPosition?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val current = saved?.takeIf { it.optString("version_id") == publication.versionId }?.optString("chapter_id")
                    val directory = repository.cache.savedDirectory(scope, work, publication.versionId)
                    val continuous = novelContinuousOfflineCount(directory, current, chapters.map { it.getString("id") }.toSet())
                    val range = continuous?.let { "从${if (current == null) "首章" else "当前"}起连续 $it 章" } ?: "连续范围待完整目录确认"
                    val toc = if (directory == null) "目录未完整缓存" else "目录已缓存 ${directory.optInt("readable_total")} 章"
                    var bytes = 0L
                    for (pub in publications.filter { it.versionId == publication.versionId }) {
                        val item = library.dao.asset(pub.assetId)
                        if (item != null) bytes += runCatching { library.localFile(item).length() }.getOrDefault(0L)
                    }
                    OfflineVersion(asset, "版本 ${publication.versionId.take(8)} · 已下载 ${chapters.size} 章 · $toc · $range · ${bytes / 1024} KiB")
                }
                versions.firstOrNull()?.let { OfflineWork(work, it.asset.title, versions) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("离线书库", style = MaterialTheme.typography.titleLarge); Text("按作品归集，各来源的已下载正文分别保留。", style = MaterialTheme.typography.bodySmall) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(tasks.filter { it.state != "completed" }, key = { "task:" + it.operation }) { task ->
            Card { Column(Modifier.padding(16.dp)) {
                Text(task.title)
                val state = when (task.state) { "queued" -> "等待下载"; "running" -> "正在下载"; "paused" -> "已暂停"; else -> "等待重试" }
                Text("$state · ${task.completed}/${task.total} 章 · ${task.failed} 章失败", style = MaterialTheme.typography.bodySmall)
                task.message.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = working == null && (task.active || !LibraryResources.offlineOnly), onClick = {
                    working = task.operation
                    coroutine.launch {
                        try { if (task.active) repository.downloads.pause(task) else repository.downloads.resume(task) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                        finally { working = null }
                    }
                }) { Text(if (task.active) "暂停" else "继续未完成章节") }
            } }
        }
        items(works, key = { it.id }) { work -> Card {
            Column(Modifier.padding(16.dp)) {
                Text(work.title, style = MaterialTheme.typography.titleMedium)
                work.versions.forEach { version -> TextButton(onClick = { onOpen(version.asset) }) { Text(version.label) } }
            }
        } }
        if (works.isEmpty() && error == null) item { Text("在阅读目录中下载章节后，这里会显示可离线阅读的作品。") }
    }
}
