package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Retained by the reader while the sheet is closed; source URLs never leave memory. */
internal class NovelReaderSourceState {
    var autoReceipts by mutableStateOf<Map<String, JSONObject>>(emptyMap())
    var autoFailures by mutableStateOf<Map<String, String>>(emptyMap())
    var autoRetry by mutableIntStateOf(0)
    var matchingOnly by mutableStateOf(false)
    var query by mutableStateOf("")
    var jobId by mutableStateOf<String?>(null)
    var jobState by mutableStateOf("")
    val searchRunning get() = jobId != null && jobState !in setOf("succeeded", "failed", "cancelled")
    var result by mutableStateOf<JSONObject?>(null)
    var selectedVersion by mutableStateOf<String?>(null)
    var receipt by mutableStateOf<JSONObject?>(null)
    var listIndex = 0
    var listOffset = 0
}

@Composable
internal fun ColumnScope.NovelReaderSourcesPanel(repository: NovelRepository, publication: NovelPublicationEntity,
    title: String, currentTitle: String, enabled: Boolean, state: NovelReaderSourceState, onOpen: (String, String?) -> Unit) {
    val coroutine = rememberCoroutineScope()
    val list = rememberLazyListState(state.listIndex, state.listOffset)
    BackHandler(state.selectedVersion != null) { state.selectedVersion = null }
    val c = runCatching { repository.connection(publication.scope) }.getOrNull()
    var versions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var work by remember(publication.scope, publication.workId) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<Job?>(null) }
    var searchAction by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var recommendation by remember(state.selectedVersion) { mutableStateOf<JSONObject?>(null) }
    val online = c != null && !LibraryResources.offlineOnly
    suspend fun load() {
        val cached = repository.dao.publications(publication.scope).filter { it.workId == publication.workId }.distinctBy { it.versionId }
            .map { JSONObject().put("id", it.versionId).put("label", "已下载来源 · ${it.versionId.take(8)}").put("cached", true) }
        versions = cached
        if (c != null) {
            val detail = repository.get(c, listOf("works", publication.workId))
            work = detail
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
    fun runSearch(stop: Boolean = false, block: suspend ((JSONObject) -> Unit) -> Unit) {
        searchAction?.cancel()
        val generation = ++searchGeneration
        searching = true; stopping = stop; searchError = null; notice = null
        searchAction = coroutine.launch {
            try { block { if (generation == searchGeneration) update(it) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == searchGeneration) searchError = e.message ?: "搜索中断，已返回结果保留" }
            finally { if (generation == searchGeneration) { searching = false; stopping = false } }
        }
    }
    fun resumeSearch() = runSearch { receive ->
        repository.sourceSearch.resumeSearch(requireNotNull(c), requireNotNull(state.jobId), receive)
    }
    fun searchSources() = runSearch { receive ->
        val connection = requireNotNull(c)
        if (state.searchRunning) repository.sourceSearch.cancel(connection, requireNotNull(state.jobId))
        val features = repository.sourceSearch.features(connection)
        state.result = null; state.jobId = null; state.jobState = ""
        if (features.autoBind) state.autoReceipts = emptyMap()
        repository.sourceSearch.search(connection, state.query,
            NovelSearchOptions(mode = if (features.searchModes) "auto" else null, workId = publication.workId.takeIf { features.autoBind }),
            onJob = { state.jobId = it; state.jobState = "queued" }, onUpdate = receive)
    }
    fun stopSearch() = runSearch(stop = true) { receive ->
        val connection = requireNotNull(c); val id = requireNotNull(state.jobId)
        receive(repository.sourceSearch.cancel(connection, id))
        if (state.searchRunning) repository.sourceSearch.resumeSearch(connection, id, receive)
        notice = if (state.jobState == "cancelled") "搜索已停止，已返回结果保留" else "搜索已完成，结果已保留"
    }
    LaunchedEffect(publication.scope, publication.workId) {
        if (state.query.isBlank()) state.query = title
        try { load() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取在线书源" }
        if (online && state.searchRunning) resumeSearch()
    }
    LaunchedEffect(c?.id, work?.optString("id"), work?.optString("title"), work?.optString("author")) {
        val connection = c ?: return@LaunchedEffect
        val target = work ?: return@LaunchedEffect
        val attempted = state.autoReceipts.keys.toMutableSet()
        var previousJob: String? = null
        var previousRetry = state.autoRetry
        snapshotFlow { Triple(state.result, state.jobId, state.autoRetry) }.collect { (result, job, retry) ->
            if (previousJob != job || previousRetry != retry) {
                attempted.clear(); attempted.addAll(state.autoReceipts.keys)
                state.autoFailures = emptyMap(); previousJob = job; previousRetry = retry
            }
            val rows = result?.novelObjects("items").orEmpty()
            if (rows.isNotEmpty() && !LibraryResources.offlineOnly) {
                try {
                    val before = state.autoReceipts.size
                    repository.sourceSearch.autoBindMatches(connection, target, rows, repository.sourceSearch.features(connection), attempted,
                        onBound = { key, receipt -> state.autoReceipts = state.autoReceipts + (key to receipt); state.autoFailures = state.autoFailures - key },
                        onFailure = { key, message -> state.autoReceipts = state.autoReceipts - key; state.autoFailures = state.autoFailures + (key to message) })
                    if (state.autoReceipts.size != before) load()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.autoFailures = state.autoFailures + ("service" to (e.message ?: "自动关联失败")) }
            }
        }
    }
    DisposableEffect(state) { onDispose { state.listIndex = list.firstVisibleItemIndex; state.listOffset = list.firstVisibleItemScrollOffset; action?.cancel(); searchGeneration++; searchAction?.cancel() } }
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
    if (busy || searching) LinearProgressIndicator(Modifier.fillMaxWidth())
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
    LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
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
        items(versions.sortedByDescending { it.getString("id") == publication.versionId }, key = { it.getString("id") }) { version ->
            val id = version.getString("id")
            OutlinedButton(onClick = { state.selectedVersion = id }, enabled = enabled && !busy &&
                ((online && version.optString("state") == "ready") || version.optBoolean("cached")), modifier = Modifier.fillMaxWidth()) {
                Text(version.optString("label").ifBlank { "未命名来源" } + if (id == publication.versionId) " · 当前" else "", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            HorizontalDivider()
            Text("搜索其他书源", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text("书名和作者一致的来源自动关联，选择后再切换阅读。", style = MaterialTheme.typography.bodySmall)
            FilterChip(state.matchingOnly, { state.matchingOnly = !state.matchingOnly }, label = { Text("只看同名同作者") })
            if (searching) Text("正在搜索，可直接选择已找到的来源。", style = MaterialTheme.typography.bodySmall)
            if (state.autoReceipts.isNotEmpty()) Text("已自动关联 ${state.autoReceipts.size} 个来源", style = MaterialTheme.typography.labelSmall)
            if (state.autoFailures.isNotEmpty()) TextButton(enabled = online && !busy && !searching, onClick = {
                if (state.result?.novelObjects("items")?.any { it.has("auto_bind") } == true) searchSources() else state.autoRetry++
            }) { Text("${state.autoFailures.size} 个来源未关联，重试") }
            OutlinedTextField(state.query, { state.query = it.take(200) }, singleLine = true, label = { Text("书名或作者") }, modifier = Modifier.fillMaxWidth(), enabled = !busy && !searching)
            Row {
                TextButton(enabled = online && enabled && !busy && !searching && state.query.isNotBlank(), onClick = ::searchSources) { Text("搜索可用书源") }
                if (searching && state.searchRunning) TextButton(enabled = !stopping, onClick = ::stopSearch) { Text(if (stopping) "正在停止" else "停止搜索") }
            }
            searchError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = online && !searching, onClick = { if (state.searchRunning) resumeSearch() else searchSources() }) {
                    Text(if (state.searchRunning) "继续获取结果" else "重新搜索")
                }
            }
            state.result?.let { result ->
                Text("找到 ${result.novelObjects("items").count(::readableNovelSource)} 个可读来源" +
                    if (result.optBoolean("truncated")) " · 当前为部分结果，可缩小关键词继续查找" else "", style = MaterialTheme.typography.labelSmall)
            }
            if (state.matchingOnly && state.result != null && novelWorkSourceResults(work, state.result!!.novelObjects("items").filter(::readableNovelSource), true).isEmpty()) {
                Text("暂无同名同作者结果，可关闭筛选查看全部。", style = MaterialTheme.typography.bodySmall)
            }
            state.receipt?.let { receipt -> TextButton(enabled = online && !busy, onClick = { execute {
                val version = repository.sourceSearch.collectionVersion(requireNotNull(c), receipt)
                load(); state.receipt = null; state.selectedVersion = version
            } }) { Text("重试获取已关联书源目录") } }
        }
        items(novelWorkSourceResults(work, state.result?.novelObjects("items").orEmpty().filter(::readableNovelSource), state.matchingOnly)
            .distinctBy(::novelSourceIdentity), key = ::novelSourceIdentity) { row ->
            OutlinedButton(enabled = enabled && online && !busy, onClick = { execute {
                val connection = requireNotNull(c)
                val receipt = state.autoReceipts[novelSourceIdentity(row)]?.takeIf { it.novelId("job_id") != null || it.novelId("version_id") != null } ?: repository.sourceSearch.collect(connection, row, publication.workId, features = repository.sourceSearch.features(connection))
                state.receipt = receipt
                val version = repository.sourceSearch.collectionVersion(connection, receipt)
                load(); state.receipt = null; state.selectedVersion = version
            } }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(row.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(row.optString("author"), row.optString("provider_name")).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(if (novelSourceIdentity(row) in state.autoReceipts) "已自动关联 · 选择章节" else "关联并选择章节", style = MaterialTheme.typography.labelSmall)
                    state.autoFailures[novelSourceIdentity(row)]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
