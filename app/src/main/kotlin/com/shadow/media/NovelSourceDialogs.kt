@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

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

internal class NovelTask(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var progress by mutableStateOf<JSONObject?>(null)
    private var task: Job? = null
    private var generation = 0
    fun cancel() { generation++; task?.cancel(); busy = false }
    fun run(block: suspend () -> Unit) {
        cancel(); val ticket = generation; busy = true; error = null
        task = scope.launch {
            try { block() }
            catch (e: TimeoutCancellationException) { if (ticket == generation) error = "等待超时；已返回内容保留，可稍后刷新任务。" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) error = novelToolError(e) }
            finally { if (ticket == generation) busy = false }
        }
    }
}
@Composable internal fun rememberNovelTask(): NovelTask {
    val scope = rememberCoroutineScope(); val task = remember(scope) { NovelTask(scope) }
    DisposableEffect(task) { onDispose { task.cancel() } }
    return task
}
@Composable internal fun NovelTaskStatus(task: NovelTask) {
    if (task.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    task.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    task.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
@Composable internal fun NovelOptionChips(title: String, value: String, choices: List<Pair<String, String>>, onValue: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { (id, name) -> FilterChip(value == id, { onValue(id) }, label = { Text(name) }) } }
}
internal fun novelTags(value: String) = value.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank).distinct()

@Composable internal fun NovelSourcePreviewSheet(repository: NovelRepository, connection: CatalogConnection, row: JSONObject,
    bindingWork: JSONObject?, onDismiss: () -> Unit, onAuthor: (String) -> Unit, onWork: (String) -> Unit, onBound: () -> Unit) {
    val context = LocalContext.current
    val task = rememberNovelTask()
    var preview by remember(row) { mutableStateOf<JSONObject?>(null) }
    var receipt by remember(row) { mutableStateOf<JSONObject?>(null) }
    var tocQuery by remember(row) { mutableStateOf("") }
    var descending by remember(row) { mutableStateOf(false) }
    fun load(toc: Boolean) = task.run { preview = mergeNovelPreview(preview, repository.sourceSearch.preview(connection, row, toc)) }
    LaunchedEffect(row) { load(false) }
    val detail = novelSourceDetail(row, preview)
    val workId = receipt?.novelId("work_id") ?: preview?.novelId("work_id") ?: row.novelId("library_work_id")
    val toc = preview?.optJSONObject("toc")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(detail.optString("name"), style = MaterialTheme.typography.headlineSmall)
                Text(detail.optString("provider_name"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss) { Text("返回结果") }
                NovelTaskStatus(task)
                preview?.novelId("message")?.let { Text(it) }; preview?.novelId("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (detail.optString("author").isNotBlank()) TextButton(onClick = { onAuthor(detail.optString("author")) }) { Text("作者：${detail.optString("author")} · 继续搜索") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !task.busy && receipt == null, onClick = { task.run {
                        receipt = repository.sourceSearch.collect(connection, detail)
                        task.message = if (receipt?.novelId("job_id") == null) "资料已收录" else "已收录，正在获取目录；失败仍保留资料"
                        receipt?.novelId("job_id")?.let { repository.sourceSearch.awaitJob(connection, it) {} }
                        task.message = "已收录，可打开作品查看可用版本"
                    } }) { Text(if (receipt != null) "已收录" else "收录作品") }
                    if (workId != null) TextButton(onClick = { onWork(workId) }) { Text("打开已收录作品") }
                    if (bindingWork != null && readableNovelSource(detail)) TextButton(enabled = !task.busy, onClick = { task.run {
                        repository.sourceSearch.bind(connection, bindingWork.getString("id"), detail); onBound()
                    } }) { Text("关联当前作品") }
                    TextButton(onClick = { try { openNovelWeb(context, row.getString("bookUrl")) } catch (e: Exception) { task.error = e.message } }) { Text("源网页") }
                }
                Text(if (readableNovelSource(detail)) "收录后由服务获取目录；目录成功后才能选择版本阅读。" else "此来源只收录资料，不提供可读正文。", style = MaterialTheme.typography.bodySmall)
                Text(detail.optString("intro").ifBlank { "暂无简介" })
                listOf("kind" to "分类", "category" to "类别", "word_count" to "字数", "latest_chapter" to "最新章节", "source_updated_at" to "来源更新", "status" to "状态").forEach { (key, title) ->
                    detail.novelId(key)?.let { Text("$title：$it", style = MaterialTheme.typography.bodyMedium) }
                }
                FlowRow {
                    TextButton(enabled = !task.busy, onClick = { load(false) }) { Text("重试详情") }
                    if (detail.novelStrings("capabilities").containsAll(listOf("detail", "toc"))) TextButton(enabled = !task.busy, onClick = { load(true) }) { Text(if (toc == null) "预览目录" else "刷新目录预览") }
                }
                Text("预览不收录、不下载正文，也不改阅读进度。", style = MaterialTheme.typography.bodySmall)
                preview?.novelId("toc_error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (toc != null) {
                item {
                    Text(if (toc.optBoolean("complete")) "完整目录预览 · ${toc.novelObjects("items").size} 章" else "部分目录 · ${toc.optInt("pages")} 页 / ${toc.novelObjects("items").size} 章", style = MaterialTheme.typography.titleMedium)
                    if (!toc.optBoolean("complete")) Text("最多预览 3 页、200 章，限时 20 秒；以下筛选只覆盖已预览部分。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(tocQuery, { tocQuery = it }, Modifier.fillMaxWidth(), label = { Text("过滤预览标题") }, singleLine = true)
                    FilterChip(descending, { descending = !descending }, label = { Text(if (descending) "倒序" else "正序") })
                }
                items(novelPreviewTitles(preview, tocQuery, descending)) { Text(it) }
            }
        }
    }
}

@Composable internal fun NovelSourcePicker(repository: NovelRepository, connection: CatalogConnection, initial: NovelSourceFilter,
    selected: Set<String>, onDismiss: () -> Unit, onApply: (NovelSourceFilter, Set<String>) -> Unit) {
    var filter by remember { mutableStateOf(initial) }
    var ids by remember { mutableStateOf(selected) }
    var tagText by remember { mutableStateOf(initial.tags.joinToString(",")) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    val task = rememberNovelTask()
    fun load(start: Int = 0) { val requested = filter.copy(tags = novelTags(tagText)); filter = requested
        task.run { val result = repository.tools.catalog(connection, requested, start); data = result; offset = start }
    }
    LaunchedEffect(Unit) { load() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("书源范围", style = MaterialTheme.typography.titleLarge)
                Text("多组取并集，标签同时满足。书源分组与作品书架分组独立。", style = MaterialTheme.typography.bodySmall)
                NovelTaskStatus(task)
                FlowRow { Button(enabled = !task.busy, onClick = { onApply(filter.copy(tags = novelTags(tagText)), ids) }) { Text("应用 · 已选 ${ids.size} 源") }
                    TextButton(onClick = { ids = emptySet() }) { Text("清空选择") }
                }
                OutlinedTextField(filter.q, { filter = filter.copy(q = it.take(160)) }, Modifier.fillMaxWidth(), label = { Text("书源名称、站点或标签") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data?.novelObjects("groups").orEmpty().forEach { group -> val id = group.getString("id")
                        FilterChip(id in filter.groups, { filter = filter.copy(groups = if (id in filter.groups) filter.groups - id else filter.groups + id) }, label = { Text(group.optString("name")) })
                    }
                }
                OutlinedTextField(tagText, { tagText = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔，AND）") })
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起筛选" else "状态、能力与排序") }
                if (advanced) {
                    NovelOptionChips("启用", filter.enabled, listOf("" to "全部", "true" to "启用", "false" to "停用")) { filter = filter.copy(enabled = it) }
                    NovelOptionChips("发现", filter.exploreEnabled, listOf("" to "全部", "true" to "发现开启", "false" to "发现关闭")) { filter = filter.copy(exploreEnabled = it) }
                    NovelOptionChips("归属", filter.groupMode, listOf("" to "全部", "ungrouped" to "未分组", "grouped" to "已分组")) { filter = filter.copy(groupMode = it) }
                    NovelOptionChips("能力", filter.capability, listOf("" to "全部", "search" to "搜索", "detail" to "详情", "toc" to "目录", "chapter" to "正文")) { filter = filter.copy(capability = it) }
                    NovelOptionChips("可用性", filter.availability, listOf("" to "全部") + listOf("直接可用", "代理可用", "需要处理", "不可用").map { it to it }) { filter = filter.copy(availability = it) }
                    NovelOptionChips("类型", filter.category, listOf("" to "全部") + data?.optJSONObject("facets")?.novelObjects("category").orEmpty().map { it.optString("value") to it.optString("value") }) { filter = filter.copy(category = it) }
                    NovelOptionChips("排序", filter.sort, listOf("priority" to "优先级", "name" to "名称", "domain" to "站点", "enabled" to "启用优先", "latency" to "响应耗时", "revision" to "修订次数")) { filter = filter.copy(sort = it) }
                }
                FlowRow {
                    Button(enabled = !task.busy, onClick = { load() }) { Text("筛选") }
                    TextButton(enabled = !task.busy, onClick = { ids = ids + data?.novelObjects("items").orEmpty().map { it.getString("id") } }) { Text("选择本页") }
                    TextButton(enabled = !task.busy, onClick = { task.run { ids = repository.tools.selection(connection, filter.copy(tags = novelTags(tagText))).toSet() } }) { Text("全选筛选结果") }
                }
                data?.let { Text("共 ${it.optInt("total")} 源；跨页保留 ${ids.size} 个选择", style = MaterialTheme.typography.bodySmall) }
            }
            items(data?.novelObjects("items").orEmpty(), key = { it.getString("id") }) { row ->
                val id = row.getString("id")
                Card(onClick = { ids = if (id in ids) ids - id else ids + id }) { Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Checkbox(id in ids, { ids = if (it) ids + id else ids - id })
                    Column(Modifier.weight(1f)) { Text(row.optString("name")); Text("${if (row.optBoolean("enabled")) "启用" else "停用"} · 发现${if (row.optBoolean("explore_enabled")) "开启" else "关闭"}", style = MaterialTheme.typography.bodySmall)
                        Text(row.novelStrings("tags").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(row.novelObjects("groups").joinToString(" · ") { it.optString("name") }.ifBlank { "未分组" }, style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            item { FlowRow {
                TextButton(enabled = !task.busy && offset > 0, onClick = { load((offset - 40).coerceAtLeast(0)) }) { Text("上一页") }
                TextButton(enabled = !task.busy && offset + 40 < (data?.optInt("total") ?: 0), onClick = { load(offset + 40) }) { Text("下一页") }
            } }
        }
    }
}

@Composable internal fun NovelSearchSettings(repository: NovelRepository, connection: CatalogConnection, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    val task = rememberNovelTask()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var exclusions by remember { mutableStateOf("") }
    var showRead by remember { mutableStateOf(true) }
    fun load() = task.run {
        data = repository.live(connection, listOf("library", "preferences"))
        exclusions = data?.optJSONObject("search")?.novelStrings("exclusions").orEmpty().joinToString("\n")
        showRead = data?.optJSONObject("search")?.optBoolean("show_read", true) ?: true
    }
    LaunchedEffect(Unit) { load() }
    ModalBottomSheet(onDismissRequest = onDismiss) { LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("搜索设置与最近记录", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
            if (data == null) TextButton(enabled = !task.busy, onClick = ::load) { Text("重新加载") }
            OutlinedTextField(exclusions, { exclusions = it }, Modifier.fillMaxWidth(), label = { Text("排除书名或作者词（每行一个）") }, minLines = 3)
            FilterChip(showRead, { showRead = !showRead }, label = { Text("显示已收录、在书架和读过标记") })
            Button(enabled = !task.busy && data != null, onClick = { task.run {
                repository.mutate(connection, listOf("library", "search-preferences"), "PUT", JSONObject().put("exclusions", JSONArray(novelTags(exclusions))).put("show_read", showRead))
                task.message = "已保存，下次搜索生效"
            } }) { Text("保存设置") }
            Text("最近 50 条去重关键词", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !task.busy && data != null, onClick = { task.run { repository.mutate(connection, listOf("library", "search-history"), "DELETE"); data = data?.let { JSONObject(it.toString()).put("history", JSONArray()) } } }) { Text("清空历史") }
        }
        items(data?.novelObjects("history").orEmpty()) { item -> TextButton(onClick = { onSearch(item.optString("q")) }) { Text(item.optString("q")) } }
    } }
}
