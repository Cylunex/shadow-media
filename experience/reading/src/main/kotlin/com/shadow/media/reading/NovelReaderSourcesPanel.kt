package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Retained by the reader while the sheet is closed; source URLs never leave memory. */
internal class NovelReaderSourceState {
    var contentsHolder: SaveableStateHolder? = null
    val contentsPages = mutableMapOf<String, MutableMap<Pair<NovelContentsQuery, String?>, JSONObject>>()
    val catalogs = mutableMapOf<Pair<String, String>, NovelReaderSourceCatalog>()
    var autoReceipts by mutableStateOf<Map<String, JSONObject>>(emptyMap())
    var autoFailures by mutableStateOf<Map<String, String>>(emptyMap())
    var autoRetry by mutableIntStateOf(0)
    var matchingOnly by mutableStateOf(false)
    var filter by mutableStateOf(NovelSourceFilter(enabled = "true", capability = "search"))
    var scopeLoaded = false
    var scopeCatalog by mutableStateOf<JSONObject?>(null)
    var searchOptions: NovelSearchOptions? = null
    var searchCandidates: List<String>? = null
    var submittedScope by mutableStateOf("")
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var jobId by mutableStateOf<String?>(null)
    var jobState by mutableStateOf("")
    val searchRunning get() = jobId != null && jobState !in setOf("succeeded", "failed", "cancelled")
    var previousResult by mutableStateOf<JSONObject?>(null)
    var result by mutableStateOf<JSONObject?>(null)
    var searchBase: JSONObject? = null
    var selectedVersion by mutableStateOf<String?>(null)
    var receipt by mutableStateOf<JSONObject?>(null)
}

internal class NovelReaderSourceCatalog {
    var versions by mutableStateOf<List<JSONObject>>(emptyList())
    var work by mutableStateOf<JSONObject?>(null)
    val list = LazyListState()
}

@Composable
internal fun ColumnScope.NovelReaderSourcesPanel(repository: NovelRepository, publication: NovelPublicationEntity,
    title: String, currentTitle: String, enabled: Boolean, state: NovelReaderSourceState,
    currentChapterId: String? = null, currentOrdinal: Int? = null, onOpen: (String, String?) -> Unit) {
    val coroutine = rememberCoroutineScope()
    val newHolder = rememberSaveableStateHolder()
    val contentsHolder = state.contentsHolder ?: newHolder.also { state.contentsHolder = it }
    val context = LocalContext.current
    val scopeStore = remember(context) { NovelSourceScopeStore(context) }
    var showScope by remember { mutableStateOf(false) }
    val catalog = remember(state, publication.scope, publication.workId) {
        state.catalogs.getOrPut(publication.scope to publication.workId) { NovelReaderSourceCatalog() }
    }
    val list = catalog.list
    BackHandler(state.selectedVersion != null) { state.selectedVersion = null }
    val c = runCatching { repository.connection(publication.scope) }.getOrNull()
    val versions = catalog.versions
    val work = catalog.work
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
    var locating by remember(state.selectedVersion) { mutableStateOf(false) }
    var locateError by remember(state.selectedVersion) { mutableStateOf<String?>(null) }
    var locateRetry by remember { mutableIntStateOf(0) }
    val online = c != null && !LibraryResources.offlineOnly
    suspend fun load() {
        val cached = repository.dao.publications(publication.scope).filter { it.workId == publication.workId }.distinctBy { it.versionId }
            .map { JSONObject().put("id", it.versionId).put("label", "已下载来源 · ${it.versionId.take(8)}").put("cached", true) }
        if (catalog.versions.isEmpty()) catalog.versions = cached
        if (online && c != null) {
            val detail = repository.get(c, listOf("works", publication.workId))
            val remote = detail.novelObjects("versions").filter { it.novelId("id") != null }.map { version ->
                if (cached.any { it.getString("id") == version.getString("id") }) JSONObject(version.toString()).put("cached", true) else version
            }
            // Keep the previous successful directory visible throughout the request.
            catalog.work = detail
            catalog.versions = remote + cached.filterNot { local -> remote.any { it.getString("id") == local.getString("id") } }
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
    fun update(job: JSONObject) {
        state.jobState = job.optString("state")
        job.optJSONObject("result")?.let {
            if (it.novelObjects("items").isNotEmpty() || state.jobState == "succeeded") state.previousResult = null
            state.result = mergeNovelSourceRetry(state.searchBase, it).also { merged ->
                state.searchCandidates?.let { candidates ->
                    val completed = merged.novelObjects("sources").mapNotNull { row -> row.novelId("provider_id") }.toSet()
                    merged.put("remaining", candidates.count { id -> id !in completed })
                }
            }
        }
    }
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
    fun searchSources(providerIds: List<String>? = null, more: Boolean = false) = runSearch { receive ->
        val connection = requireNotNull(c)
        val fresh = providerIds == null && !more
        val requestedFilter = state.filter
        val scopeLabel = novelReaderScopeLabel(requestedFilter, state.scopeCatalog)
        val features = repository.sourceSearch.features(connection)
        val options = if (fresh) NovelSearchOptions(groups = requestedFilter.groups,
            mode = "auto".takeIf { features.searchModes }, workId = publication.workId.takeIf { features.autoBind })
            else state.searchOptions ?: NovelSearchOptions(mode = "auto".takeIf { features.searchModes }, workId = publication.workId.takeIf { features.autoBind })
        // Resolve tags through the same selection API as the source browser. Never treat an empty scope as all sources.
        val candidates = if (fresh) repository.sourceSearch.scope(connection, requestedFilter) else state.searchCandidates
        val completed = state.result?.novelObjects("sources").orEmpty().mapNotNull { it.novelId("provider_id") }.toSet()
        val requested = providerIds ?: candidates?.filter { fresh || it !in completed }
        require(requested == null || requested.isNotEmpty()) { "当前范围没有可搜索书源，请调整标签或分组" }
        val batch = if (options.mode == null) requested?.take(options.limit) else requested
        val keyword = if (fresh) state.query.trim() else state.submitted
        val base = state.result.takeUnless { fresh }
        if (state.searchRunning) repository.sourceSearch.cancel(connection, requireNotNull(state.jobId))
        repository.sourceSearch.search(connection, keyword,
            options.copy(mode = if (providerIds != null && options.mode != null) "all" else options.mode,
                providerIds = batch, excludeIds = if (more && batch == null) completed.toList() else emptyList()),
            onJob = {
                state.submitted = keyword
                state.searchOptions = options; state.searchCandidates = candidates
                if (fresh) { state.submittedScope = scopeLabel; state.autoReceipts = emptyMap() }
                if (fresh) state.previousResult = state.result
                state.searchBase = base; state.result = base
                state.jobId = it; state.jobState = "queued"
            }, onUpdate = receive)
    }

    fun retrySearch() {
        if (state.searchRunning) { resumeSearch(); return }
        val statuses = state.result?.novelObjects("sources").orEmpty().associateBy { it.optString("provider_id") }
        val ids = state.searchCandidates?.filter { id -> statuses[id]?.let(::novelSourceNeedsRetry) != false }
        if (!ids.isNullOrEmpty()) searchSources(ids) else searchSources()
    }

    fun stopSearch() = runSearch(stop = true) { receive ->
        val connection = requireNotNull(c); val id = requireNotNull(state.jobId)
        receive(repository.sourceSearch.cancel(connection, id))
        if (state.searchRunning) repository.sourceSearch.resumeSearch(connection, id, receive)
        notice = if (state.jobState == "cancelled") "搜索已停止，已返回结果保留" else "搜索已完成，结果已保留"
    }
    LaunchedEffect(publication.scope, publication.workId) {
        if (!state.scopeLoaded) {
            c?.let { scopeStore.load("${it.id}:reader")?.let { saved -> state.filter = saved.filter } }
            state.scopeLoaded = true
        }
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
    DisposableEffect(state) { onDispose { action?.cancel(); searchGeneration++; searchAction?.cancel() } }
    LaunchedEffect(state.selectedVersion, currentTitle, locateRetry) {
        recommendation = null; locateError = null; locating = false
        val version = state.selectedVersion ?: return@LaunchedEffect
        if (version == publication.versionId || currentTitle.isBlank()) return@LaunchedEffect
        locating = online
        try {
            if (online && c != null) {
                recommendation = repository.get(c, listOf("works", publication.workId, "open"), listOf("version_id" to version, "compact" to "true"))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { locateError = "暂未定位当前章，可从目录选章。" }
        finally { locating = false }
    }
    if (showScope && c != null) NovelReaderSourceScope(repository, c, state.filter, state.scopeCatalog,
        onCatalog = { state.scopeCatalog = it }, onDismiss = { showScope = false }, onApply = { filter ->
            state.filter = filter
            scopeStore.save("${c.id}:reader", NovelSavedSourceScope(filter, emptySet()))
            showScope = false
        })
    Column(Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (busy || searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(error ?: notice ?: "选择来源查看目录，选择章节后切换阅读。", maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.selectedVersion?.let { version ->
        TextButton(onClick = { state.selectedVersion = null }) { Text("返回书源列表") }
        Text(versions.firstOrNull { it.getString("id") == version }?.optString("label") ?: "所选来源", style = MaterialTheme.typography.titleSmall)
        Row {
            TextButton(enabled = enabled && !busy && !locating && recommendation?.optString("quality") != "needs_selection", onClick = { onOpen(version, null) }) { Text("续读此来源") }
            recommendation?.let { opening ->
                val position = opening.optJSONObject("position")
                val label = when (opening.optString("mapping_basis")) {
                    "version_progress" -> "恢复此来源上次位置"
                    "body_hash" -> "接着读相同正文"
                    "title" -> "从同名章开头读"
                    "locator" -> "接着读对应章节"
                    else -> "继续阅读"
                }
                if (position != null && opening.optString("quality") != "needs_selection") TextButton(enabled = enabled && !busy, onClick = {
                    onOpen(version, position.optString("chapter_id").takeIf(String::isNotBlank).takeIf { opening.optString("quality") != "exact" && !opening.optBoolean("offset_preserved") })
                }) { Text(label) }
                if (opening.optString("quality") == "needs_selection") Text("此来源章节无法唯一定位，请从目录选择。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (locating) Text("正在定位当前章，目录可先浏览。", style = MaterialTheme.typography.bodySmall)
        locateError?.let { TextButton(onClick = { locateRetry++ }) { Text("$it 重试定位") } }
        Text("也可从目录选择章节。换源后可返回原位置。", style = MaterialTheme.typography.bodySmall)
        val position = recommendation?.optJSONObject("position")
        val locatedId = if (version == publication.versionId) currentChapterId else position?.novelId("chapter_id")
        val locatedOrdinal = if (version == publication.versionId) currentOrdinal else recommendation?.takeIf { locatedId != null && it.has("chapter_ordinal") && !it.isNull("chapter_ordinal") }?.optInt("chapter_ordinal")
        contentsHolder.SaveableStateProvider(version) {
            NovelContentsPanel(repository, c, publication.workId, version, enabled && !busy,
                onOpen = { onOpen(version, it.getString("id")) }, modifier = Modifier.weight(1f), contentScope = publication.scope,
                currentId = locatedId, currentOrdinal = locatedOrdinal,
                currentLabel = if (version == publication.versionId) "正在阅读" else "对应阅读位置",
                retainedPages = state.contentsPages.getOrPut(version) { linkedMapOf() })
        }
        return
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton(enabled = online, modifier = Modifier.weight(1f), onClick = { showScope = true }) {
            Text("搜索范围 · ${novelReaderScopeLabel(state.filter, state.scopeCatalog)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { coroutine.launch { list.scrollToItem(versions.size + 1) } }) { Text("在线搜索") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val remaining = state.result?.optInt("remaining", 0) ?: 0
        if (remaining > 0) Button(enabled = online && !busy && !searching && !state.searchRunning,
            onClick = { searchSources(more = true) }) { Text("继续搜索 $remaining 源") }
        if (searching && state.searchRunning) TextButton(enabled = !stopping, onClick = ::stopSearch) { Text(if (stopping) "正在停止" else "停止搜索") }
        TextButton(onClick = { coroutine.launch { list.scrollToItem(0) } }) { Text("回到顶部") }
    }
    val visibleResult = state.previousResult ?: state.result
    LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "versions-header") {
            Text("已关联来源 · ${versions.size}", style = MaterialTheme.typography.titleMedium)
            val binding = versions.firstOrNull { it.getString("id") == publication.versionId }?.novelId("binding_id")
            if (binding != null) TextButton(enabled = online && enabled && !busy, onClick = { execute {
                val connection = requireNotNull(c)
                val receipt = repository.mutate(connection, listOf("bindings", binding, "refresh"), "POST")
                state.receipt = receipt
                repository.sourceSearch.collectionVersion(connection, receipt)
                load(); state.receipt = null; notice = "目录检查完成，可在来源列表选择更新后的版本"
            } }) { Text("检查当前书源目录") }
        }
        items(versions.sortedByDescending { it.getString("id") == publication.versionId }, key = { "version:" + it.getString("id") }) { version ->
            val id = version.getString("id")
            OutlinedButton(onClick = { state.selectedVersion = id }, enabled = enabled && !busy &&
                ((online && version.optString("state") == "ready") || version.optBoolean("cached")), modifier = Modifier.fillMaxWidth()) {
                Text(version.optString("label").ifBlank { "未命名来源" } + if (id == publication.versionId) " · 当前" else "", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item(key = "search-controls") {
            HorizontalDivider()
            Text("搜索其他书源", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text("书名和作者一致的来源自动关联，选择后再切换阅读。", style = MaterialTheme.typography.bodySmall)
            FilterChip(state.matchingOnly, { state.matchingOnly = !state.matchingOnly }, label = { Text("只看同名同作者") })
            Text("标签和分组决定搜索范围；同名同作者只筛选结果，不扩大关联条件。", style = MaterialTheme.typography.bodySmall)
            if (state.submitted.isNotBlank()) Text("当前结果：${state.submitted} · ${state.submittedScope.ifBlank { "原搜索范围" }}；修改范围后重新搜索生效。", style = MaterialTheme.typography.labelSmall)
            if (state.previousResult != null) Text(if (searching || state.searchRunning) "等待本次搜索结果，暂显上次结果。" else "本次暂无新结果，保留上次结果。", style = MaterialTheme.typography.bodySmall)
            if (searching) Text("正在搜索，可直接选择已找到的来源。", style = MaterialTheme.typography.bodySmall)
            if (state.autoReceipts.isNotEmpty()) Text("已自动关联 ${state.autoReceipts.size} 个来源", style = MaterialTheme.typography.labelSmall)
            if (state.autoFailures.isNotEmpty()) TextButton(enabled = online && !busy && !searching, onClick = {
                val ids = state.result?.novelObjects("sources").orEmpty().filter(::novelSourceNeedsRetry).mapNotNull { it.novelId("provider_id") }.distinct()
                if (state.result?.novelObjects("items")?.any { it.has("auto_bind") } == true && ids.isNotEmpty()) searchSources(ids) else state.autoRetry++
            }) { Text("${state.autoFailures.size} 个来源未关联，重试") }
            OutlinedTextField(state.query, { state.query = it.take(200) }, singleLine = true, label = { Text("书名或作者") }, modifier = Modifier.fillMaxWidth(), enabled = !busy && !searching)
            Row {
                TextButton(enabled = online && enabled && !busy && !searching && state.query.isNotBlank(), onClick = { searchSources() }) { Text("搜索可用书源") }
            }
            searchError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = online && !searching, onClick = ::retrySearch) {
                    Text(if (state.searchRunning) "继续获取结果" else if (state.jobState == "failed") "重试原搜索" else "重新搜索")
                }
            }
            state.result?.let { result ->
                if (!searching && result.novelObjects("items").none(::readableNovelSource)) Text("本批暂无可读来源，可继续搜索或调整范围。", style = MaterialTheme.typography.bodySmall)
                state.searchCandidates?.let { Text("已查 ${result.novelObjects("sources").size} / ${it.size} 个书源", style = MaterialTheme.typography.labelSmall) }
                Text("找到 ${result.novelObjects("items").count(::readableNovelSource)} 个可读来源" +
                    if (result.optBoolean("truncated")) " · 当前为部分结果，可缩小关键词继续查找" else "", style = MaterialTheme.typography.labelSmall)
                val retrySources = result.novelObjects("sources").filter(::novelSourceNeedsRetry)
                    .mapNotNull { it.novelId("provider_id") }.distinct()
                if (retrySources.isNotEmpty()) TextButton(enabled = online && !busy && !searching, onClick = { searchSources(retrySources) }) {
                    Text("重试未完成的 ${retrySources.size} 个来源")
                }
            }
            if (state.matchingOnly && state.result != null && novelWorkSourceResults(work, state.result!!.novelObjects("items").filter(::readableNovelSource), true).isEmpty()) {
                Text("暂无同名同作者结果，可关闭筛选查看全部。", style = MaterialTheme.typography.bodySmall)
            }
            state.receipt?.let { receipt -> TextButton(enabled = online && !busy, onClick = { execute {
                val version = repository.sourceSearch.collectionVersion(requireNotNull(c), receipt)
                load(); state.receipt = null; state.selectedVersion = version
            } }) { Text("重试获取已关联书源目录") } }
        }
        items(novelWorkSourceResults(work, visibleResult?.novelObjects("items").orEmpty().filter(::readableNovelSource), state.matchingOnly)
            .distinctBy(::novelSourceIdentity), key = { "result:" + novelSourceIdentity(it) }) { row ->
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
