@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

import androidx.core.content.edit

import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

/** Kept by the connection screen, so opening a work never discards the current search session. */
internal class NovelBrowserState(initial: String = "") {
    var autoReceipts by mutableStateOf<Map<String, JSONObject>>(emptyMap())
    var autoFailures by mutableStateOf<Map<String, String>>(emptyMap())
    var autoRetry by mutableIntStateOf(0)
    var matchingOnly by mutableStateOf(false)
    var searchRequest by mutableIntStateOf(0)
    var pendingSearch = false
    fun requestSearch(value: String) {
        keyword = value.trim().take(200)
        selectMode(false)
        pendingSearch = keyword.isNotBlank()
        searchRequest++
    }
    var keyword by mutableStateOf(initial)
    var submitted by mutableStateOf("")
    var precise by mutableStateOf(false)
    var mode by mutableStateOf("auto")
    var features by mutableStateOf<NovelSourceFeatures?>(null)
    var searchJobId by mutableStateOf<String?>(null)
    var searchJobState by mutableStateOf("")
    val searchRunning get() = searchJobId != null && searchJobState !in setOf("succeeded", "failed", "cancelled")
    var jobIds: List<String> = emptyList()
    var outsideIds: List<String> = emptyList()
    var baseRows: List<JSONObject> = emptyList()
    var baseSources: List<JSONObject> = emptyList()
    var matchedCount by mutableIntStateOf(0)
    var serverRemaining by mutableIntStateOf(0)
    var concurrency by mutableIntStateOf(0)
    var truncated by mutableStateOf(false)
    fun updateSearch(job: JSONObject) {
        searchJobState = job.optString("state")
        val result = job.optJSONObject("result") ?: return
        val updates = result.novelObjects("sources")
        rows = (baseRows + result.novelObjects("items")).associateBy(::novelResultKey).values.toList()
            .sortedByDescending { row -> searchOptions.precise && listOf("name", "author", "kind").any { row.optString(it).equals(submitted, true) } }
        sources = baseSources.filterNot { old -> updates.any { it.optString("provider_id") == old.optString("provider_id") } } + updates
        val unfinished = jobIds.filterNot { id -> updates.any { it.optString("provider_id") == id } }
        pendingIds = if (searchJobState == "succeeded") emptyList() else unfinished
        remaining = outsideIds + if (searchJobState == "succeeded") unfinished else emptyList()
        matchedCount = result.optInt("matched_count", result.novelObjects("items").size)
        serverRemaining = result.optInt("remaining", unfinished.size) + outsideIds.size
        concurrency = result.optInt("concurrency")
        truncated = truncated || result.optBoolean("truncated")
    }
    var searchOptions by mutableStateOf(NovelSearchOptions())
    var searchChoices by mutableStateOf<List<JSONObject>>(emptyList())
    var filter by mutableStateOf(NovelSourceFilter(enabled = "true", capability = "search"))
    var choices by mutableStateOf<List<JSONObject>>(emptyList())
    var remaining by mutableStateOf<List<String>>(emptyList())
    var pendingIds by mutableStateOf<List<String>>(emptyList())
    var pendingPage by mutableIntStateOf(1)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var rows by mutableStateOf<List<JSONObject>>(emptyList())
    var sources by mutableStateOf<List<JSONObject>>(emptyList())
    var preferences by mutableStateOf<JSONObject?>(null)
    var detail by mutableStateOf<JSONObject?>(null)
    var explore by mutableStateOf(false)
    fun selectMode(discovery: Boolean) {
        explore = discovery
        filter = filter.copy(enabled = if (discovery) "" else "true", capability = if (discovery) "" else "search",
            exploreEnabled = if (discovery) "true" else "")
    }
    var backRequests by mutableIntStateOf(0)
    var columnIndex = 0
    var columnOffset = 0
    fun backWithinBrowser(): Boolean {
        if (detail != null) { detail = null; return true }
        if (explore && entry != null) { entry = null; backRequests++; return true }
        return false
    }
    var provider by mutableStateOf<JSONObject?>(null)
    var entries by mutableStateOf<List<JSONObject>>(emptyList())
    var entry by mutableStateOf<JSONObject?>(null)
    var page by mutableIntStateOf(1)
    var discovery by mutableStateOf<JSONObject?>(null)
    var discoveryQuery by mutableStateOf("")
    var listIndex = 0
    var listOffset = 0
}

@Composable internal fun NovelSourceBrowser(repository: NovelRepository, connection: CatalogConnection,
    state: NovelBrowserState, onWork: (String) -> Unit, onManage: () -> Unit, bindingWork: JSONObject? = null, onBound: () -> Unit = {}, onRead: (NovelSourceOpening) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scopeStore = remember(context) { NovelSourceScopeStore(context) }
    val scopeKey = "${connection.id}:${if (state.explore) "explore" else "search"}"
    var loadedScopeKey by remember(state) { mutableStateOf<String?>(null) }
    val preferences = LocalContext.current.getSharedPreferences("novel_source_columns", android.content.Context.MODE_PRIVATE)
    var favorites by remember(connection.id) { mutableStateOf(runCatching { JSONObject(preferences.getString(connection.id, "{}") ?: "{}").novelObjects("items") }.getOrDefault(emptyList())) }
    var recent by remember(connection.id) { mutableStateOf(runCatching { JSONObject(preferences.getString(connection.id + ":recent", "{}") ?: "{}").novelObjects("items") }.getOrDefault(emptyList())) }
    var pinned by remember(connection.id) { mutableStateOf(preferences.getStringSet(connection.id + ":pinned", emptySet()).orEmpty().toSet()) }
    fun saveFavorites(rows: List<JSONObject>) { favorites = rows.take(50); preferences.edit { putString(connection.id, JSONObject().put("items", JSONArray(favorites)).toString()) } }
    var task by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var searchAfterChoices by remember { mutableStateOf(false) }
    val list = rememberLazyListState(state.listIndex, state.listOffset)
    val visibleChoices = state.choices.filter { state.selected.isEmpty() || it.optString("id") in state.selected }
    fun run(block: suspend () -> Unit) {
        task?.cancel(); val ticket = ++generation; busy = true; error = null
        task = scope.launch {
            try { block() }
            catch (e: TimeoutCancellationException) { if (ticket == generation) error = "等待超时，已返回的内容保留。可重试或稍后刷新。" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) error = novelToolError(e) }
            finally { if (ticket == generation) { busy = false; jobId = null } }
        }
    }
    suspend fun choices() {
        state.features = repository.sourceSearch.features(connection)
        val f = state.filter
        val browser = repository.live(connection, listOf("source-browser"), listOf("mode" to if (state.explore) "explore" else "search") + f.groups.map { "group_id" to it })
        // The browser already applies its mode and group scope. The bulk-selection endpoint
        // has a 5000-source limit and is only needed for additional user filters.
        val defaultScope = NovelBrowserState().apply { selectMode(state.explore) }.filter
        val allowed = if (f.copy(groups = emptyList()) == defaultScope) null else repository.tools.selection(connection, f).toSet()
        currentCoroutineContext().ensureActive()
        state.choices = browser.novelObjects("items").filter { allowed == null || it.getString("id") in allowed }

    }
    suspend fun resumeSearch() {
        val id = state.searchJobId ?: return
        repository.sourceSearch.resumeSearch(connection, id, state::updateSearch)
    }
    fun search(ids: List<String>? = null, page: Int = 1, append: Boolean = false) {
        if (!append && state.keyword.isBlank()) return
        val previousJob = state.searchJobId.takeIf { state.searchRunning }
        run {
            if (previousJob != null) repository.sourceSearch.cancel(connection, previousJob)
            state.searchJobId = null; state.searchJobState = ""; notice = null
            if (!append) {
                state.submitted = state.keyword.trim().take(200)
                state.rows = emptyList(); state.sources = emptyList(); state.detail = null; state.truncated = false
                choices(); state.searchChoices = state.choices
                if (state.features?.autoBind == true) state.autoReceipts = emptyMap()
                state.searchOptions = NovelSearchOptions(state.precise, state.filter.groups,
                    mode = state.mode.takeIf { state.features?.searchModes == true },
                    workId = bindingWork?.novelId("id").takeIf { state.features?.autoBind == true })
            }
            val candidates = ids ?: state.choices.map { it.getString("id") }.filter { state.selected.isEmpty() || it in state.selected }
            require(candidates.isNotEmpty()) { "当前范围没有可搜索书源，可调整分组或筛选。" }
            require(candidates.size <= 5000) { "一次最多搜索 5000 个书源，请缩小范围。" }
            val options = state.searchOptions.copy(page = page, mode = if (candidates.size == 1 && state.searchOptions.mode != null) "batch" else state.searchOptions.mode)
            val batch = if (options.mode in setOf("auto", "all")) candidates else candidates.take(options.limit)
            // Preserve any unsearched scope when retrying a failed source or fetching its next page.
            state.outsideIds = (state.remaining.filterNot { it in candidates } + candidates.drop(batch.size)).distinct()
            if (!append) state.outsideIds = candidates.drop(batch.size)
            state.jobIds = batch; state.pendingIds = batch; state.pendingPage = page
            state.matchedCount = 0; state.serverRemaining = batch.size + state.outsideIds.size; state.concurrency = 0
            state.remaining = state.outsideIds; state.baseRows = state.rows; state.baseSources = state.sources
            if (!append) {
                try { repository.mutate(connection, listOf("library", "search-history"), "POST", JSONObject().put("q", state.submitted)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = "搜索历史未保存：${novelToolError(e)}" }
            }
            repository.sourceSearch.search(connection, state.submitted, options.copy(providerIds = batch),
                onJob = { state.searchJobId = it; state.searchJobState = "queued" }, onUpdate = state::updateSearch)
        }
    }
    fun discover(provider: JSONObject, entry: JSONObject, page: Int, refresh: Boolean = false) {
        if (state.entry == null) { state.columnIndex = list.firstVisibleItemIndex; state.columnOffset = list.firstVisibleItemScrollOffset }
        val visit = JSONObject().put("provider", provider.getString("id")).put("entry", entry.getString("key")).put("name", provider.optString("name") + " · " + entry.optString("name"))
        recent = (listOf(visit) + recent.filterNot { it.optString("provider") == visit.optString("provider") && it.optString("entry") == visit.optString("entry") }).take(8)
        preferences.edit { putString(connection.id + ":recent", JSONObject().put("items", JSONArray(recent)).toString()) }
        val same = state.provider?.optString("id") == provider.optString("id") && state.entry?.optString("key") == entry.optString("key") && state.page == page
        state.provider = provider; state.entry = entry; state.page = page
        if (!same) state.discovery = null
        run {
            val path = listOf("source-browser", provider.getString("id"), entry.getString("key"))
            val cached = repository.live(connection, path, listOf("page" to page.toString()))
            state.discovery = cached
            if (refresh || cached.optBoolean("stale")) {
                val receipt = repository.mutate(connection, path + "refresh", "POST", JSONObject().put("page", page))
                receipt.novelId("job_id")?.let { jobId = it; repository.sourceSearch.awaitJob(connection, it) {} }
                state.discovery = repository.live(connection, path, listOf("page" to page.toString()))
                notice = receipt.novelId("message")
            }
        }
    }
    LaunchedEffect(scopeKey, loadedScopeKey, state.filter, state.searchRequest) {
        if (loadedScopeKey != scopeKey) {
            val saved = scopeStore.load(scopeKey)
            if (saved != null) { state.filter = saved.filter; state.selected = saved.selected }
            else if (loadedScopeKey != null) {
                state.filter = NovelBrowserState().apply { selectMode(state.explore) }.filter
                state.selected = emptySet()
            }
            loadedScopeKey = scopeKey
            return@LaunchedEffect
        }
        if (state.pendingSearch) {
            state.pendingSearch = false
            search()
        } else if (searchAfterChoices) {
            searchAfterChoices = false
            search()
        } else run {
            choices()
            if (!state.explore && state.searchRunning) resumeSearch()
        }
    }
    LaunchedEffect(connection.id) {
        try { state.preferences = repository.live(connection, listOf("library", "preferences")) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = novelToolError(e) }
    }
    LaunchedEffect(connection.id, bindingWork?.novelId("id")) {
        val work = bindingWork ?: return@LaunchedEffect
        val attempted = state.autoReceipts.keys.toMutableSet()
        var previousSearch: String? = null
        var previousRetry = state.autoRetry
        snapshotFlow { Triple(state.rows, state.searchJobId, state.autoRetry) }.collect { (rows, searchId, retry) ->
            if (previousSearch != searchId || previousRetry != retry) {
                attempted.clear(); attempted.addAll(state.autoReceipts.keys)
                state.autoFailures = emptyMap(); previousSearch = searchId; previousRetry = retry
            }
            if (rows.isNotEmpty()) {
                try {
                    val features = state.features ?: repository.sourceSearch.features(connection)
                    repository.sourceSearch.autoBindMatches(connection, work, rows, features, attempted,
                        onBound = { key, receipt -> state.autoReceipts = state.autoReceipts + (key to receipt); state.autoFailures = state.autoFailures - key },
                        onFailure = { key, message -> state.autoReceipts = state.autoReceipts - key; state.autoFailures = state.autoFailures + (key to message) })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.autoFailures = state.autoFailures + ("service" to novelToolError(e)) }
            }
        }
    }
    DisposableEffect(state) { onDispose { state.listIndex = list.firstVisibleItemIndex; state.listOffset = list.firstVisibleItemScrollOffset; task?.cancel(); generation++ } }
    var handledBack by remember { mutableIntStateOf(state.backRequests) }
    LaunchedEffect(state.backRequests) {
        if (handledBack != state.backRequests) {
            handledBack = state.backRequests
            task?.cancel(); generation++; busy = false; jobId = null
            list.scrollToItem(state.columnIndex, state.columnOffset)
        }
    }
    BackHandler(state.detail != null || state.explore && state.entry != null) { state.backWithinBrowser() }
    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.explore && state.entry != null) Surface(tonalElevation = 2.dp) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                TextButton(enabled = !busy, onClick = { state.backWithinBrowser() }) { Text("返回栏目") }
                TextButton(enabled = !busy && state.page > 1, onClick = { discover(state.provider!!, state.entry!!, state.page - 1) }) { Text("上一页") }
                TextButton(enabled = !busy, onClick = { discover(state.provider!!, state.entry!!, state.page, true) }) { Text("刷新第 ${state.page} 页") }
                TextButton(enabled = !busy && state.entry?.optBoolean("paginated") == true && state.page < 100 && state.discovery?.novelObjects("items")?.isNotEmpty() == true,
                    onClick = { discover(state.provider!!, state.entry!!, state.page + 1) }) { Text("下一页") }
            }
        }
        if (!state.explore && (state.submitted.isNotBlank() || busy)) Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("${state.rows.size} 个来源结果 · 剩余 ${state.remaining.size} 个书源", style = MaterialTheme.typography.labelSmall)
                FlowRow {
                    if (state.remaining.isNotEmpty()) Button(enabled = !busy && !state.searchRunning, onClick = { search(state.remaining, append = true) }) { Text("继续搜索 ${state.remaining.size} 源") }
                    if (state.pendingIds.isNotEmpty() && !state.searchRunning) TextButton(enabled = !busy, onClick = { search(state.pendingIds, state.pendingPage, true) }) { Text("重试未完成") }
                    if (busy) TextButton(onClick = {
                        val id = state.searchJobId
                        task?.cancel(); generation++; busy = false; jobId = null
                        if (id != null) run { state.updateSearch(repository.sourceSearch.cancel(connection, id)); if (state.searchRunning) resumeSearch() }
                    }) { Text("停止搜索") }
                    TextButton(onClick = { scope.launch { list.scrollToItem(0) } }) { Text("回到顶部") }
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (bindingWork == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!state.explore, { state.selectMode(false) }, label = { Text("在线搜索") })
                    FilterChip(state.explore, { state.selectMode(true) }, label = { Text("书源发现") })
                } else Text("为《${bindingWork.optString("title")}》查找其他来源", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { choose = true }, label = { Text("范围 · ${visibleChoices.size} 源") })
                    TextButton(onClick = { settings = true }) { Text("搜索设置与历史") }
                    TextButton(onClick = onManage) { Text("管理书源") }
                }
                if (bindingWork != null && !state.explore) {
                    Text("书名和作者一致的结果会自动关联；不会改变当前阅读位置。", style = MaterialTheme.typography.bodySmall)
                    FilterChip(state.matchingOnly, { state.matchingOnly = !state.matchingOnly }, label = { Text("只看同名同作者") })
                    if (state.autoReceipts.isNotEmpty()) Text("已自动关联 ${state.autoReceipts.size} 个来源", style = MaterialTheme.typography.labelSmall)
                    if (state.autoFailures.isNotEmpty()) TextButton(onClick = { if (state.features?.autoBind == true) search() else state.autoRetry++ }) { Text("${state.autoFailures.size} 个来源未关联，重试") }
                }
                if (!state.explore) {
                    NovelSearchField(state.keyword, { state.keyword = it.take(200) }, true, { search() }, "搜索书名、作者或分类")
                    FilterChip(state.precise, { state.precise = !state.precise }, label = { Text("精确搜索") })
                    Text("匹配书名、作者或分类中的关键词，完全匹配优先。", style = MaterialTheme.typography.bodySmall)
                    if (state.features?.searchModes == true) {
                        NovelOptionChips("搜索方式", state.mode, listOf("auto" to "自动续查", "batch" to "单批", "all" to "全部书源")) { state.mode = it }
                        Text("每批 12 源；自动续查遇到有匹配的批次后暂停。全部书源查询所选范围的当前页。", style = MaterialTheme.typography.bodySmall)
                    } else if (state.features != null) Text("当前服务使用分批搜索，每批 12 源。", style = MaterialTheme.typography.bodySmall)
                    if (state.searchJobId != null) Text("本任务匹配 ${state.matchedCount} 条 · 待完成 ${state.serverRemaining} 源" +
                        if (state.concurrency > 0) " · ${state.concurrency} 路并发" else "", style = MaterialTheme.typography.bodySmall)
                    if (state.truncated) Text("结果已达服务端保留上限；达到上限不会停止其余书源查询。请缩小范围重搜，以免遗漏匹配项。", color = MaterialTheme.colorScheme.error)
                    if (!busy && state.searchRunning) TextButton(onClick = { run { resumeSearch() } }) { Text("继续查看原搜索任务") }
                    if (state.submitted.isNotBlank()) Text("当前结果：${state.submitted} · ${if (state.searchOptions.precise) "精确" else "普通"}搜索；修改范围后重新搜索生效。", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) TextButton(onClick = {
                    val id = if (state.explore) jobId else state.searchJobId
                    task?.cancel(); generation++; busy = false; jobId = null
                    if (id != null) run {
                        val cancelled = repository.sourceSearch.cancel(connection, id)
                        notice = "已请求取消，已返回结果保留"
                        if (!state.explore) { state.updateSearch(cancelled); if (state.searchRunning) resumeSearch() }
                    }
                }) { Text("取消当前请求") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (error != null) TextButton(enabled = !busy, onClick = { if (state.explore && state.provider != null && state.entry != null) discover(state.provider!!, state.entry!!, state.page, true) else if (state.searchRunning) run { resumeSearch() } else if (state.pendingIds.isNotEmpty()) search(state.pendingIds, state.pendingPage, true) else search() }) { Text("重试") }
            }
            if (state.explore) {
                if (state.entry == null) {
                    item { OutlinedTextField(state.discoveryQuery, { state.discoveryQuery = it.take(100) }, singleLine = true,
                        label = { Text("查找发现书源") }, modifier = Modifier.fillMaxWidth()) }
                    items((favorites + recent).distinctBy { it.optString("provider") + ":" + it.optString("entry") }, key = { it.optString("provider") + ":" + it.optString("entry") }) { favorite ->
                        Row {
                            TextButton(enabled = !busy, onClick = {
                                val provider = state.choices.firstOrNull { it.optString("id") == favorite.optString("provider") }
                                if (provider == null) error = "此栏目来源已停用或不在当前筛选范围"
                                else run {
                                    val entries = repository.live(connection, listOf("source-browser", provider.getString("id"), "entries")).novelObjects("items")
                                    val entry = entries.firstOrNull { it.optString("key") == favorite.optString("entry") }
                                    if (entry == null) error = "栏目已变化，请重新选择" else discover(provider, entry, 1)
                                }
                            }) { Text(favorite.optString("name")) }
                            TextButton(onClick = {
                                saveFavorites(favorites - favorite); recent = recent - favorite
                                preferences.edit { putString(connection.id + ":recent", JSONObject().put("items", JSONArray(recent)).toString()) }
                            }) { Text(if (favorite in favorites) "移除收藏" else "移除记录") }
                        }
                    }
                    item { Text("选择书源与栏目；发现开关独立于搜索开关。", style = MaterialTheme.typography.bodySmall) }
                    items(visibleChoices.filter { it.optString("name").contains(state.discoveryQuery.trim(), true) }
                        .sortedByDescending { it.optString("id") in pinned }, key = { it.getString("id") }) { provider ->
                        val id = provider.getString("id")
                        TextButton(onClick = {
                            pinned = if (id in pinned) pinned - id else pinned + id
                            preferences.edit { putStringSet(connection.id + ":pinned", pinned) }
                        }) { Text(if (id in pinned) "取消发现置顶" else "置顶发现") }
                        TextButton(enabled = !busy, onClick = { state.provider = provider; state.entries = emptyList(); run {
                            state.entries = repository.live(connection, listOf("source-browser", provider.getString("id"), "entries")).novelObjects("items")
                        } }) { Text("${provider.optString("name")} · ${provider.optInt("entry_count")} 栏目") }
                        if (state.provider?.optString("id") == provider.optString("id")) FlowRow {
                            state.entries.forEach { entry -> AssistChip(onClick = { discover(provider, entry, 1) }, label = { Text("${entry.optString("kind")} · ${entry.optString("name")}") }) }
                        }
                    }
                    if (!busy && visibleChoices.isEmpty() && error == null) item { Text("当前范围没有启用发现的来源，或来源尚无发现能力。") }
                } else {
                    item { Text("${state.provider?.optString("name")} · ${state.entry?.optString("name")} · 第 ${state.page} 页")
                        FlowRow { TextButton(onClick = { state.backWithinBrowser() }) { Text("选择其他栏目") }
                            TextButton(onClick = {
                                val favorite = JSONObject().put("provider", state.provider!!.getString("id")).put("entry", state.entry!!.getString("key"))
                                    .put("name", state.provider!!.optString("name") + " · " + state.entry!!.optString("name"))
                                saveFavorites(listOf(favorite) + favorites.filterNot { it.optString("provider") == favorite.optString("provider") && it.optString("entry") == favorite.optString("entry") })
                                notice = "栏目已收藏，可在栏目选择页直接进入"
                            }) { Text("收藏栏目") }
                        }
                        state.discovery?.novelId("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.discovery?.optBoolean("stale") == true) Text("显示上次成功内容，更新失败时继续保留。", style = MaterialTheme.typography.bodySmall)
                    }
                    items(state.discovery?.novelObjects("items").orEmpty(), key = ::novelResultKey) { row -> NovelSourceResultCard(row) { state.detail = row } }
                }
            } else {
                if (state.rows.isEmpty() && !busy && state.submitted.isNotBlank() && error == null) item { Text("本批未找到匹配项；仍可继续其他书源或单源下一页。") }
                if (bindingWork != null && state.matchingOnly && state.rows.isNotEmpty() && novelWorkSourceResults(bindingWork, state.rows, true).isEmpty()) item {
                    Text("暂无同名同作者结果，可关闭筛选查看全部；未知作者不会自动关联。", style = MaterialTheme.typography.bodySmall)
                }
                items(novelResultGroups(novelWorkSourceResults(bindingWork, state.rows, state.matchingOnly)), key = { novelSourceIdentity(it.first()) }) { group -> NovelSourceResultGroup(group, { state.detail = it }) { row ->
                    if (novelSourceIdentity(row) in state.autoReceipts) Text("已自动关联", style = MaterialTheme.typography.labelSmall)
                    state.autoFailures[novelSourceIdentity(row)]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } }
                items(state.sources, key = { it.optString("provider_id") }) { source ->
                    Column {
                        Text("${source.optString("name")} · ${if (source.optBoolean("ok")) "${source.optInt("count")} 条 / 原始 ${source.optInt("raw_count", source.optInt("count"))} 条" else source.optString("message")}", style = MaterialTheme.typography.bodySmall)
                        val pid = source.optString("provider_id")
                        val next = novelSourceNextPage(source, state.searchChoices.any { it.optString("id") == pid && it.optBoolean("search_paginated") })
                        FlowRow { if (next != null) TextButton(enabled = !busy, onClick = { search(listOf(pid), next, true) }) { Text("此源第 $next 页") }
                            if (novelSourceNeedsRetry(source)) TextButton(enabled = !busy, onClick = { search(listOf(pid), source.optInt("page", 1), true) }) {
                                Text(if (source.optBoolean("ok")) "补全此源关联" else "重试此源")
                            }
                        }
                    }
                }
            }
        }
    }
    if (choose) NovelSourcePicker(repository, connection, state.filter, state.selected, onDismiss = { choose = false }, onApply = { f, ids ->
        scopeStore.save(scopeKey, NovelSavedSourceScope(f, ids))
        state.filter = f; state.selected = ids; state.entry = null; state.provider = null; state.discovery = null; choose = false
    })
    if (settings) NovelSearchSettings(repository, connection, onDismiss = { settings = false }, onSearch = { state.keyword = it; settings = false; search() })
    state.detail?.let { row -> NovelSourcePreviewSheet(repository, connection, row, bindingWork, onDismiss = { state.detail = null }, onAuthor = {
        state.detail = null; state.keyword = it
        if (state.explore) { searchAfterChoices = true; state.selectMode(false) } else search()
    }, onWork = onWork, onBound = onBound, features = state.features, automaticReceipt = state.autoReceipts[novelSourceIdentity(row)], onRead = { state.detail = null; onRead(it) }) }
}

@Composable internal fun NovelSourceResultCard(row: JSONObject, onClick: () -> Unit) {
    Card(onClick = onClick) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.optString("name"), style = MaterialTheme.typography.titleMedium)
        Text("${row.optString("author").ifBlank { "作者待补全" }} · ${row.optString("provider_name")}", style = MaterialTheme.typography.bodySmall)
        row.optJSONObject("stages")?.let { stages ->
            Text("来源表现 · " + listOf("search" to "搜索", "toc" to "目录", "chapter" to "正文").joinToString(" · ") { (key, label) ->
                val status = stages.optJSONObject(key)
                label + when (status?.optString("state")) { "passed" -> "已验证"; "empty" -> "无结果"; "failed" -> "失败"; "unsupported" -> "不支持"; else -> "未测" }
            }, style = MaterialTheme.typography.labelSmall)
            stages.optJSONObject("chapter")?.takeIf { it.optString("state") == "passed" && it.has("duration_ms") }?.let { measurement ->
                val measuredAt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date((measurement.optDouble("at") * 1000).toLong()))
                Text("此源上次正文 ${measurement.optInt("result_count")} 字 · ${measurement.optInt("duration_ms")} ms · $measuredAt", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(row.optString("intro"), maxLines = 3, style = MaterialTheme.typography.bodySmall)
        Text(listOfNotNull(if (readableNovelSource(row)) "可尝试获取目录" else "仅资料", row.novelId("library_work_id")?.let { "已收录" },
            if (row.optBoolean("on_shelf")) "在书架" else null, if (row.optBoolean("has_read")) "读过" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
    } }
}

internal fun novelToolError(e: Exception) = if (e is NovelApiException && e.status in setOf(404, 405) && e.detail.optString("message") in setOf("", "Not Found", "Method Not Allowed")) "此服务版本尚未提供该功能，请更新 Novel 服务后重试。" else e.message ?: "操作失败，请重试"
internal fun openNovelWeb(context: android.content.Context, value: String) {
    val uri = value.toUri()
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "网页地址无效" }
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
}
