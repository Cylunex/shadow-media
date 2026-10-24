package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Retained by the reader while the sheet is closed; source URLs never leave memory. */
internal class NovelReaderSourceState {
    var query by mutableStateOf("")
    var jobId by mutableStateOf<String?>(null)
    var jobState by mutableStateOf("")
    var result by mutableStateOf<JSONObject?>(null)
    var selectedVersion by mutableStateOf<String?>(null)
    var receipt by mutableStateOf<JSONObject?>(null)
}

@Composable
internal fun ColumnScope.NovelReaderSourcesPanel(repository: NovelRepository, publication: NovelPublicationEntity,
    title: String, currentTitle: String, enabled: Boolean, state: NovelReaderSourceState, onOpen: (String, String?) -> Unit) {
    val coroutine = rememberCoroutineScope()
    val c = runCatching { repository.connection(publication.scope) }.getOrNull()
    var versions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<Job?>(null) }
    var recommendation by remember(state.selectedVersion) { mutableStateOf<JSONObject?>(null) }
    val online = c != null && !LibraryResources.offlineOnly
    suspend fun load() {
        val cached = repository.dao.publications(publication.scope).filter { it.workId == publication.workId }.distinctBy { it.versionId }
            .map { JSONObject().put("id", it.versionId).put("label", "已下载来源 · ${it.versionId.take(8)}").put("cached", true) }
        versions = cached
        if (c != null) {
            val detail = repository.get(c, listOf("works", publication.workId))
            val remote = detail.novelObjects("versions").filter { it.novelId("id") != null }.map { version ->
                if (cached.any { it.getString("id") == version.getString("id") }) JSONObject(version.toString()).put("cached", true) else version
            }
            versions = remote + cached.filterNot { local -> remote.any { it.getString("id") == local.getString("id") } }
        }
    }
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        action = coroutine.launch {
            try { block() }
            catch (e: TimeoutCancellationException) { error = "目录仍在获取，当前阅读位置已保留，可稍后重试获取目录。" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "书源获取失败，请重试" }
            finally { busy = false }
        }
    }
    fun update(job: JSONObject) { state.jobState = job.optString("state"); state.result = job.optJSONObject("result") ?: state.result }
    LaunchedEffect(publication.scope, publication.workId) {
        if (state.query.isBlank()) state.query = title
        try { load() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取在线书源" }
        if (c != null && state.jobId != null && state.jobState !in setOf("succeeded", "failed", "cancelled")) execute {
            repository.sourceSearch.resumeSearch(c, requireNotNull(state.jobId), ::update)
        }
    }
    DisposableEffect(state) { onDispose { action?.cancel() } }
    LaunchedEffect(state.selectedVersion, currentTitle) {
        recommendation = null
        val version = state.selectedVersion ?: return@LaunchedEffect
        if (version == publication.versionId || currentTitle.isBlank()) return@LaunchedEffect
        try {
            val saved = repository.cache.savedDirectory(publication.scope, publication.workId, version)
            val page = if (saved != null) novelSavedDirectoryContents(saved, NovelContentsQuery(version, q = currentTitle, limit = 200))
                else c?.let { repository.get(it, listOf("works", publication.workId, "contents"), NovelContentsQuery(version, q = currentTitle, limit = 200).parameters()) }
            if (page != null && page.optInt("total") <= 200) recommendation = page.novelObjects("items")
                .filter { it.optBoolean("readable") && it.optString("title") == currentTitle }.singleOrNull()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Explicit TOC selection remains available. */ }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    state.selectedVersion?.let { version ->
        TextButton(onClick = { state.selectedVersion = null }) { Text("返回书源列表") }
        Text(versions.firstOrNull { it.getString("id") == version }?.optString("label") ?: "所选来源", style = MaterialTheme.typography.titleSmall)
        Row {
            TextButton(enabled = enabled && !busy, onClick = { onOpen(version, null) }) { Text("续读此来源") }
            recommendation?.let { row -> TextButton(enabled = enabled && !busy, onClick = { onOpen(version, row.getString("id")) }) { Text("接着读当前章") } }
        }
        Text("也可从目录选择章节。换源后可返回原位置。", style = MaterialTheme.typography.bodySmall)
        NovelContentsPanel(repository, c, publication.workId, version, enabled && !busy,
            onOpen = { onOpen(version, it.getString("id")) }, modifier = Modifier.weight(1f), contentScope = publication.scope)
        return
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text("切换阅读书源", style = MaterialTheme.typography.titleMedium)
            val binding = versions.firstOrNull { it.getString("id") == publication.versionId }?.novelId("binding_id")
            if (binding != null) TextButton(enabled = online && enabled && !busy, onClick = { execute {
                val connection = requireNotNull(c)
                val receipt = repository.mutate(connection, listOf("bindings", binding, "refresh"), "POST")
                state.receipt = receipt
                repository.sourceSearch.collectionVersion(connection, receipt)
                load(); state.receipt = null; notice = "目录检查完成，可在来源列表选择更新后的版本"
            } }) { Text("检查当前书源目录") }
        }
        items(versions, key = { it.getString("id") }) { version ->
            val id = version.getString("id")
            OutlinedButton(onClick = { state.selectedVersion = id }, enabled = enabled && !busy &&
                (version.optString("state") == "ready" || version.optBoolean("cached")), modifier = Modifier.fillMaxWidth()) {
                Text(version.optString("label").ifBlank { "未命名来源" } + if (id == publication.versionId) " · 当前" else "", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            HorizontalDivider()
            Text("搜索其他书源", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(state.query, { state.query = it.take(200) }, singleLine = true, label = { Text("书名或作者") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row {
                TextButton(enabled = online && enabled && !busy && state.query.isNotBlank(), onClick = { execute {
                    val connection = requireNotNull(c)
                    val features = repository.sourceSearch.features(connection)
                    state.result = null
                    repository.sourceSearch.search(connection, state.query, NovelSearchOptions(mode = if (features.searchModes) "auto" else null),
                        onJob = { state.jobId = it; state.jobState = "queued" }, onUpdate = ::update)
                } }) { Text("搜索可用书源") }
                if (busy && state.jobId != null && state.jobState !in setOf("succeeded", "failed", "cancelled")) TextButton(onClick = {
                    val id = state.jobId ?: return@TextButton
                    action?.cancel(); busy = false
                    execute { update(repository.sourceSearch.cancel(requireNotNull(c), id)) }
                }) { Text("停止搜索") }
            }
            state.result?.let { result ->
                Text("找到 ${result.novelObjects("items").count(::readableNovelSource)} 个可读来源" +
                    if (result.optBoolean("truncated")) " · 当前为部分结果，可缩小关键词继续查找" else "", style = MaterialTheme.typography.labelSmall)
            }
            state.receipt?.let { receipt -> TextButton(enabled = online && !busy, onClick = { execute {
                val version = repository.sourceSearch.collectionVersion(requireNotNull(c), receipt)
                load(); state.receipt = null; state.selectedVersion = version
            } }) { Text("重试获取已关联书源目录") } }
        }
        items(state.result?.novelObjects("items").orEmpty().filter(::readableNovelSource).distinctBy { it.optString("provider_id") to it.optString("bookUrl") }) { row ->
            OutlinedButton(enabled = enabled && online && !busy, onClick = { execute {
                val connection = requireNotNull(c)
                val receipt = repository.sourceSearch.collect(connection, row, publication.workId, features = repository.sourceSearch.features(connection))
                state.receipt = receipt
                val version = repository.sourceSearch.collectionVersion(connection, receipt)
                load(); state.receipt = null; state.selectedVersion = version
            } }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(row.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(row.optString("author"), row.optString("provider_name")).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text("关联并选择章节", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
