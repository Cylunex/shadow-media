@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Native discovery only. Neither server page URLs nor external site pages are navigation targets. */
@Composable internal fun NovelRankingsScreen(repository: NovelRepository, connection: CatalogConnection, onWork: (String) -> Unit, onSearch: (String) -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var loadedSelection by rememberSaveable(connection.id) { mutableStateOf<String?>(null) }
    var provider by rememberSaveable(connection.id) { mutableStateOf("local") }
    var kind by rememberSaveable(connection.id) { mutableStateOf("heat") }
    var boardKey by rememberSaveable(connection.id) { mutableStateOf("") }
    var period by rememberSaveable(connection.id) { mutableStateOf("total") }
    var category by rememberSaveable(connection.id) { mutableStateOf("") }
    var index by remember { mutableStateOf<JSONObject?>(null) }
    var indexError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sourceEditor by remember { mutableStateOf(false) }
    var authenticationRequired by remember { mutableStateOf(false) }
    val boards = remember(index) { NovelRankingBoard.parse(index ?: JSONObject()) }
    val selection = NovelRankingSelection(provider, kind, boardKey, period, category).resolved(boards)
    val external = boards.filter { it.providerId == provider }
    val editable = !LibraryResources.offlineOnly && page?.has("_cached_at") != true

    suspend fun fetch(selected: NovelRankingSelection, offset: Int = 0): JSONObject {
        val (path, query) = selected.request(offset)
        return repository.get(connection, path, query)
    }
    suspend fun refreshBoard(selected: NovelRankingSelection) {
        working = true
        try {
            val result = repository.mutate(connection, listOf("rankings", selected.provider, selected.key, "refresh"), "POST")
            val job = result.optString("job_id").takeUnless { it.isBlank() || it == "null" }
            if (job != null) {
                notice = "正在更新榜单，保留上次结果…"
                withTimeout(45_000) {
                    while (true) {
                        delay(1500)
                        val status = repository.get(connection, listOf("jobs", job))
                        check(!status.has("_cached_at")) { "网络暂不可用，榜单仍在服务端更新，可稍后刷新查看" }
                        when (status.optString("state")) {
                            "succeeded" -> break
                            "failed", "cancelled" -> error(status.optString("error").ifBlank { "榜单更新未完成，已保留上次结果" })
                        }
                    }
                }
            }
            val refreshed = fetch(selected)
            listState.scrollToItem(0)
            page = refreshed
            notice = result.optString("message").takeIf(String::isNotBlank) ?: "榜单已更新"
        } finally { working = false }
    }
    fun operation(block: suspend () -> Unit) {
        if (working || loading) return
        scope.launch {
            working = true; error = null
            try { block() }
            catch (e: TimeoutCancellationException) { notice = "服务端仍在更新，稍后点刷新查看；已保留当前榜单" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { authenticationRequired = e is NovelApiException && e.status == 401; error = e.message ?: "操作失败，请重试" }
            finally { working = false }
        }
    }
    LaunchedEffect(connection.id, reload) {
        try { index = repository.get(connection, listOf("rankings")); indexError = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { authenticationRequired = e is NovelApiException && e.status == 401; indexError = e.message ?: "榜单来源读取失败" }
    }
    LaunchedEffect(connection.id, selection, reload) {
        if (!selection.local && selection.key.isBlank()) {
            page = null; loading = false
            error = if (index != null) "此来源已停用或没有可用榜单，请切换来源。" else null
            return@LaunchedEffect
        }
        loading = true; page = null; error = null; notice = null
        try {
            val result = fetch(selection)
            if (loadedSelection != null && loadedSelection != selection.toString()) listState.scrollToItem(0)
            loadedSelection = selection.toString()
            page = result; loading = false
            val current = page!!
            if (!selection.local && !LibraryResources.offlineOnly && !current.has("_cached_at") && current.optBoolean("stale") &&
                System.currentTimeMillis() / 1000 - current.optDouble("attempted_at", 0.0) > 30) refreshBoard(selection)
        } catch (e: TimeoutCancellationException) { notice = "服务端仍在更新，稍后点刷新查看；已保留当前榜单" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { authenticationRequired = e is NovelApiException && e.status == 401; error = e.message ?: "榜单读取失败，请重试" }
        finally { loading = false; working = false }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(provider == "local", enabled = !working, onClick = { provider = "local"; boardKey = ""; period = "total" }, label = { Text("我的书库") }) }
            items(boards.distinctBy { it.providerId }, key = { it.providerId }) { source ->
                FilterChip(provider == source.providerId, enabled = !working, onClick = { provider = source.providerId; boardKey = ""; period = "total"; category = "" }, label = { Text(source.providerName) })
            }
            item { TextButton(enabled = !working && !LibraryResources.offlineOnly, onClick = { sourceEditor = true; error = null }) { Text("添加来源") } }
        }
        if (selection.local) {
            RankingChoices(kind, NOVEL_LOCAL_RANKINGS.toList(), !working) { kind = it }
            if (page?.novelObjects("categories")?.isNotEmpty() == true) RankingChoices(category,
                listOf("" to "全部分类") + page!!.novelObjects("categories").map { it.getString("id") to it.optString("name") }, !working) { category = it }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(NOVEL_RANKING_PERIODS.toList()) { (id, label) ->
                    FilterChip(selection.period == id, enabled = !working && external.any { it.period == id },
                        onClick = { period = id; boardKey = "" }, label = { Text(label) })
                }
            }
            RankingChoices(selection.key, external.filter { it.period == selection.period }.map { it.key to listOf(it.name, it.category).filter(String::isNotBlank).joinToString(" · ") }, !working) { boardKey = it }
        }
        if (loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (page == null) Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (loading) "正在读取榜单…" else "暂无可用榜单，可切换来源或刷新。", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            indexError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (authenticationRequired && connection.oidcIssuer.isNotBlank()) TextButton(onClick = onLogin) { Text("重新登录统一账号") }
            if (!loading) TextButton(onClick = { reload++ }) { Text("重试") }
        } else LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selection.local) NOVEL_LOCAL_RANKINGS[selection.kind].orEmpty() else page?.optString("name").orEmpty(),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !loading && !working && !LibraryResources.offlineOnly, onClick = {
                        if (selection.local || page?.has("_cached_at") == true) scope.launch { listState.scrollToItem(0); reload++ } else operation { refreshBoard(selection) }
                    }) { Text("刷新") }
                }
                Text(if (selection.local) "仅统计自己的书库与评分 · 总榜" else "${NOVEL_RANKING_PERIODS[selection.period]} · 保留来源原始排名与指标",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                page?.let {
                    Text("${it.optString("metric")} · ${it.optInt("total")} 本", style = MaterialTheme.typography.labelMedium)
                    if (it.optDouble("fetched_at", 0.0) > 0) Text("获取于 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date((it.optDouble("fetched_at") * 1000).toLong()))}${if (it.optBoolean("stale")) " · 缓存待更新" else ""}", style = MaterialTheme.typography.bodySmall)
                    if (it.has("_cached_at")) Text("本机缓存 · 联网后可刷新", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    if (it.optString("error").isNotBlank()) Text("${it.optString("error")}；已保留上次结果", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (authenticationRequired && connection.oidcIssuer.isNotBlank()) TextButton(onClick = onLogin) { Text("重新登录统一账号") }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                indexError?.let { Text("榜单来源暂不可用：$it", style = MaterialTheme.typography.bodySmall) }
            }
            if (!loading && page?.novelObjects("items").isNullOrEmpty()) item {
                Text(if (selection.local) "这个榜单还没有作品，试试其他分类或榜单。" else "尚无榜单结果，可刷新获取；来源失败时不会生成虚构排名。")
            }
            items(page?.novelObjects("items").orEmpty(), key = { if (selection.local) it.getJSONObject("work").getString("id") else it.getInt("index").toString() }) { row ->
                val work = if (selection.local) row.getJSONObject("work") else JSONObject().put("title", row.optString("name"))
                    .put("author", row.optString("author")).put("description", row.optString("intro"))
                val workId = if (selection.local) work.getString("id") else row.optString("work_id").takeUnless { it == "null" }.orEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.optInt("rank").toString(), Modifier.widthIn(min = 28.dp).padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = if (row.optInt("rank") <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        NovelWorkCard(work, !loading) { if (workId.isNotBlank()) onWork(workId) else onSearch(row.optString("name")) }
                        if (!selection.local) Text(listOf(row.optString("score"), when {
                            !row.isNull("change") -> row.optInt("change").let { if (it > 0) "↑ $it" else if (it < 0) "↓ ${-it}" else "排名持平" }
                            row.optBoolean("new_entry") -> "上次未上榜"
                            else -> "首次观测"
                        }).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                        if (selection.local && !row.isNull("rating")) Text("我的评分 ${row.optInt("rating")} / 5", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (workId.isNotBlank()) TextButton(enabled = editable && !working && !work.optBoolean("on_shelf"), onClick = { operation {
                                repository.mutate(connection, listOf("me", "books", workId), "PATCH", JSONObject().put("on_shelf", true))
                                notice = "已加入书架"
                                if (selection.local) { work.put("on_shelf", true); page = JSONObject(page!!.toString()) }
                            } }) { Text(if (work.optBoolean("on_shelf")) "已在书架" else "加入书架") }
                            else TextButton(enabled = editable && !working && page?.optString("token").orEmpty().length == 64, onClick = { operation {
                                try {
                                    val result = repository.mutate(connection, listOf("rankings", selection.provider, selection.key, "collect"), "POST",
                                        JSONObject().put("token", page!!.getString("token")).put("index", row.getInt("index")))
                                    onWork(result.getString("work_id"))
                                } catch (e: NovelApiException) {
                                    if (e.status != 409) throw e
                                    val latest = fetch(selection); listState.scrollToItem(0); page = latest
                                    notice = "榜单已更新，请在最新榜单中重新选择作品"
                                }
                            } }) { Text("收录资料") }
                            if (!selection.local) TextButton(onClick = { onSearch(row.optString("name")) }) { Text("搜索书库") }
                        }
                    }
                }
            }
            if ((page?.novelObjects("items")?.size ?: 0) < (page?.optInt("total") ?: 0)) item {
                OutlinedButton(enabled = !loading && !working, modifier = Modifier.fillMaxWidth(), onClick = { operation {
                    val previous = requireNotNull(page)
                    val next = fetch(selection, previous.novelObjects("items").size)
                    if (!sameNovelRankingSnapshot(previous, next, selection.local)) {
                        val latest = fetch(selection); listState.scrollToItem(0); page = latest
                        notice = "榜单已更新，已返回最新榜单开头"
                    } else {
                        next.put("items", JSONArray().also { result -> (previous.novelObjects("items") + next.novelObjects("items")).forEach(result::put) })
                        page = next
                    }
                } }) { Text("加载更多") }
            }
            if (selection.local && boards.isEmpty() && indexError == null) item {
                Text("还没有配置外部榜单；已在 Novel 配置的榜单会显示在上方来源中。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (sourceEditor) RankingSourceSheet(working, error, onDismiss = { sourceEditor = false }, onSave = { body -> operation {
        val result = repository.mutate(connection, listOf("rankings", "websites"), "POST", body)
        index = repository.get(connection, listOf("rankings"))
        sourceEditor = false
        if (result.optBoolean("enabled")) { provider = result.getString("provider_id"); boardKey = ""; period = "total"; category = "" }
        else notice = "榜单已保存；该来源目前停用，已有启用状态已保留"
    } })
}

@Composable private fun RankingChoices(selected: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (id, label) -> FilterChip(selected == id, enabled = enabled, onClick = { onSelect(id) }, label = { Text(label) }) }
    }
}

@Composable private fun RankingSourceSheet(busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val values = remember { mutableStateMapOf("name" to "", "base_url" to "", "total" to "", "month" to "", "week" to "", "day" to "") }
    var template by remember { mutableStateOf("qidian") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("添加榜单来源", style = MaterialTheme.typography.titleLarge); Text("填入自己的榜单地址。只提供公开静态榜单的来源适用，未提供的榜期留空。", style = MaterialTheme.typography.bodySmall) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(listOf("name" to "来源名称", "base_url" to "站点地址")) { (key, label) ->
                OutlinedTextField(values[key].orEmpty(), { values[key] = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = if (key == "base_url") ({ Text("https://example.com") }) else null)
            }
            item { RankingChoices(template, listOf("qidian" to "图文榜", "biquge" to "通用列表"), !busy) { template = it } }
            items(NOVEL_RANKING_PERIODS.toList()) { (key, label) ->
                OutlinedTextField(values[key].orEmpty(), { values[key] = it }, label = { Text("${label}地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item { Button(enabled = !busy && values["name"].orEmpty().isNotBlank() && values["base_url"].orEmpty().isNotBlank() &&
                NOVEL_RANKING_PERIODS.keys.any { values[it].orEmpty().isNotBlank() }, onClick = { onSave(JSONObject(values.toMap()).put("template", template)) },
                modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存榜单来源") } }
        }
    }
}
