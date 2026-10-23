@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

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
import org.json.JSONArray
import org.json.JSONObject

/** Kept by the connection screen, so opening a work never discards the current search session. */
internal class NovelBrowserState(initial: String = "") {
    var keyword by mutableStateOf(initial)
    var submitted by mutableStateOf("")
    var precise by mutableStateOf(false)
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
    var provider by mutableStateOf<JSONObject?>(null)
    var entries by mutableStateOf<List<JSONObject>>(emptyList())
    var entry by mutableStateOf<JSONObject?>(null)
    var page by mutableIntStateOf(1)
    var discovery by mutableStateOf<JSONObject?>(null)
    var listIndex = 0
    var listOffset = 0
}

@Composable internal fun NovelSourceBrowser(repository: NovelRepository, connection: CatalogConnection,
    state: NovelBrowserState, onWork: (String) -> Unit, onManage: () -> Unit, bindingWork: JSONObject? = null, onBound: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
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
        val f = state.filter
        val browser = repository.live(connection, listOf("source-browser"), listOf("mode" to if (state.explore) "explore" else "search") + f.groups.map { "group_id" to it })
        val allowed = repository.tools.selection(connection, f).toSet()
        currentCoroutineContext().ensureActive()
        state.choices = browser.novelObjects("items").filter { it.getString("id") in allowed }

    }
    fun search(ids: List<String>? = null, page: Int = 1, append: Boolean = false) {
        if (!append && state.keyword.isBlank()) return
        run {
            if (!append) {
                state.submitted = state.keyword.trim().take(200)
                state.searchOptions = NovelSearchOptions(state.precise, state.filter.groups)
                state.rows = emptyList(); state.sources = emptyList(); state.detail = null
                choices(); state.searchChoices = state.choices
            }
            val candidates = ids ?: (if (state.selected.isEmpty()) state.choices.map { it.getString("id") } else state.choices.map { it.getString("id") }.filter { it in state.selected })
            require(candidates.isNotEmpty()) { "当前范围没有可搜索书源，可调整分组或筛选。" }
            val batch = candidates.take(24)
            state.pendingIds = batch; state.pendingPage = page
            if (ids == null || ids == state.remaining) state.remaining = candidates.drop(24)
            val oldRows = state.rows
            val oldSources = state.sources
            if (!append) {
                try { repository.mutate(connection, listOf("library", "search-history"), "POST", JSONObject().put("q", state.submitted)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = "搜索历史未保存：${novelToolError(e)}" }
            }
            repository.sourceSearch.search(connection, state.submitted, state.searchOptions.copy(providerIds = batch, page = page),
                onJob = { jobId = it }, onUpdate = { update ->
                    val result = update.optJSONObject("result")
                    state.rows = (oldRows + result?.novelObjects("items").orEmpty()).associateBy(::novelResultKey).values.toList()
                        .sortedByDescending { row -> state.searchOptions.precise && listOf("name", "author", "kind").any { row.optString(it).equals(state.submitted, true) } }
                    val updated = result?.novelObjects("sources").orEmpty()
                    state.pendingIds = batch.filterNot { id -> updated.any { it.optString("provider_id") == id } }
                    state.sources = (oldSources.filterNot { old -> updated.any { it.optString("provider_id") == old.optString("provider_id") } } + updated)
                })
        }
    }
    fun discover(provider: JSONObject, entry: JSONObject, page: Int, refresh: Boolean = false) {
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
    LaunchedEffect(connection.id, state.explore, state.filter) { run { choices(); if (searchAfterChoices) { searchAfterChoices = false; search() } } }
    LaunchedEffect(connection.id) {
        try { state.preferences = repository.live(connection, listOf("library", "preferences")) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = novelToolError(e) }
    }
    DisposableEffect(state) { onDispose { state.listIndex = list.firstVisibleItemIndex; state.listOffset = list.firstVisibleItemScrollOffset; task?.cancel(); generation++ } }
    BackHandler(state.detail != null) { state.detail = null }
    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(state = list, modifier = Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (bindingWork == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!state.explore, { state.explore = false; state.filter = state.filter.copy(capability = "search", exploreEnabled = "") }, label = { Text("在线搜索") })
                    FilterChip(state.explore, { state.explore = true; state.filter = state.filter.copy(capability = "", exploreEnabled = "true") }, label = { Text("书源发现") })
                } else Text("为《${bindingWork.optString("title")}》查找其他来源", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { choose = true }, label = { Text("范围 · ${visibleChoices.size} 源") })
                    TextButton(onClick = { settings = true }) { Text("搜索设置与历史") }
                    TextButton(onClick = onManage) { Text("管理书源") }
                }
                if (!state.explore) {
                    NovelSearchField(state.keyword, { state.keyword = it.take(200) }, true, { search() }, "搜索书名、作者或分类")
                    FilterChip(state.precise, { state.precise = !state.precise }, label = { Text("精确搜索") })
                    Text("匹配书名、作者或分类中的关键词，完全匹配优先。", style = MaterialTheme.typography.bodySmall)
                    if (state.submitted.isNotBlank()) Text("当前结果：${state.submitted} · ${if (state.searchOptions.precise) "精确" else "普通"}搜索；修改范围后重新搜索生效。", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) TextButton(onClick = {
                    val id = jobId; task?.cancel(); generation++; busy = false; jobId = null
                    if (id != null) run { repository.sourceSearch.cancel(connection, id); notice = "已请求取消，已返回结果保留" }
                }) { Text("取消当前请求") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (error != null) TextButton(enabled = !busy, onClick = { if (state.explore && state.provider != null && state.entry != null) discover(state.provider!!, state.entry!!, state.page, true) else if (state.pendingIds.isNotEmpty()) search(state.pendingIds, state.pendingPage, true) else search() }) { Text("重试") }
            }
            if (state.explore) {
                if (state.entry == null) {
                    item { Text("选择书源与栏目；发现开关独立于搜索开关。", style = MaterialTheme.typography.bodySmall) }
                    items(visibleChoices, key = { it.getString("id") }) { provider ->
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
                        FlowRow { TextButton(onClick = { task?.cancel(); generation++; busy = false; state.entry = null }) { Text("选择其他栏目") }
                            TextButton(enabled = !busy, onClick = { discover(state.provider!!, state.entry!!, state.page, true) }) { Text("刷新本页") }
                        }
                        state.discovery?.novelId("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.discovery?.optBoolean("stale") == true) Text("显示上次成功内容，更新失败时继续保留。", style = MaterialTheme.typography.bodySmall)
                    }
                    items(state.discovery?.novelObjects("items").orEmpty(), key = ::novelResultKey) { row -> NovelSourceResultCard(row) { state.detail = row } }
                    item { FlowRow {
                        TextButton(enabled = !busy && state.page > 1, onClick = { discover(state.provider!!, state.entry!!, state.page - 1) }) { Text("上一页") }
                        TextButton(enabled = !busy && state.entry?.optBoolean("paginated") == true && state.page < 100 && state.discovery?.novelObjects("items")?.isNotEmpty() == true,
                            onClick = { discover(state.provider!!, state.entry!!, state.page + 1) }) { Text("下一页") }
                    } }
                }
            } else {
                if (state.rows.isEmpty() && !busy && state.submitted.isNotBlank() && error == null) item { Text("本批未找到匹配项；仍可继续其他书源或单源下一页。") }
                items(state.rows, key = ::novelResultKey) { row -> NovelSourceResultCard(row) { state.detail = row } }
                if (state.pendingIds.isNotEmpty() && !busy) item { TextButton(onClick = { search(state.pendingIds, state.pendingPage, true) }) { Text("重试尚未返回的 ${state.pendingIds.size} 个书源") } }
                if (state.remaining.isNotEmpty()) item { Button(enabled = !busy, onClick = { search(state.remaining, append = true) }) { Text("继续搜索其他 ${state.remaining.size} 个书源") } }
                items(state.sources, key = { it.optString("provider_id") }) { source ->
                    Column {
                        Text("${source.optString("name")} · ${if (source.optBoolean("ok")) "${source.optInt("count")} 条 / 原始 ${source.optInt("raw_count", source.optInt("count"))} 条" else source.optString("message")}", style = MaterialTheme.typography.bodySmall)
                        val pid = source.optString("provider_id")
                        val next = novelSourceNextPage(source, state.searchChoices.any { it.optString("id") == pid && it.optBoolean("search_paginated") })
                        FlowRow { if (next != null) TextButton(enabled = !busy, onClick = { search(listOf(pid), next, true) }) { Text("此源第 $next 页") }
                            if (!source.optBoolean("ok")) TextButton(enabled = !busy, onClick = { search(listOf(pid), source.optInt("page", 1), true) }) { Text("重试此源") }
                        }
                    }
                }
            }
        }
    }
    if (choose) NovelSourcePicker(repository, connection, state.filter, state.selected, onDismiss = { choose = false }, onApply = { f, ids ->
        state.filter = f; state.selected = ids; state.entry = null; state.provider = null; state.discovery = null; choose = false
    })
    if (settings) NovelSearchSettings(repository, connection, onDismiss = { settings = false }, onSearch = { state.keyword = it; settings = false; search() })
    state.detail?.let { row -> NovelSourcePreviewSheet(repository, connection, row, bindingWork, onDismiss = { state.detail = null }, onAuthor = {
        state.detail = null; state.keyword = it
        if (state.explore) { searchAfterChoices = true; state.explore = false; state.filter = state.filter.copy(capability = "search", exploreEnabled = "") } else search()
    }, onWork = onWork, onBound = onBound) }
}

@Composable internal fun NovelSourceResultCard(row: JSONObject, onClick: () -> Unit) {
    Card(onClick = onClick) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.optString("name"), style = MaterialTheme.typography.titleMedium)
        Text("${row.optString("author").ifBlank { "作者待补全" }} · ${row.optString("provider_name")}", style = MaterialTheme.typography.bodySmall)
        Text(row.optString("intro"), maxLines = 3, style = MaterialTheme.typography.bodySmall)
        Text(listOfNotNull(if (readableNovelSource(row)) "可尝试获取目录" else "仅资料", row.novelId("library_work_id")?.let { "已收录" },
            if (row.optBoolean("on_shelf")) "在书架" else null, if (row.optBoolean("has_read")) "读过" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
    } }
}

internal fun novelToolError(e: Exception) = if (e is NovelApiException && e.status in setOf(404, 405)) "此服务版本尚未提供该功能，请更新 Novel 服务后重试。" else e.message ?: "操作失败，请重试"
internal fun openNovelWeb(context: android.content.Context, value: String) {
    val uri = value.toUri()
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "网页地址无效" }
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
}
