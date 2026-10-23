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

internal data class NovelIntakeAction(val read: Boolean = false, val chapterUrl: String? = null)

@Composable internal fun NovelSourcePreviewSheet(repository: NovelRepository, connection: CatalogConnection, row: JSONObject,
    bindingWork: JSONObject?, onDismiss: () -> Unit, onAuthor: (String) -> Unit, onWork: (String) -> Unit, onBound: () -> Unit,
    features: NovelSourceFeatures? = null, onRead: (NovelSourceOpening) -> Unit = {}) {
    val context = LocalContext.current
    val task = rememberNovelTask()
    var supported by remember(row) { mutableStateOf(features) }
    var preview by remember(row) { mutableStateOf<JSONObject?>(null) }
    var receipt by remember(row) { mutableStateOf<JSONObject?>(null) }
    var version by remember(row) { mutableStateOf<String?>(null) }
    var chosenWork by remember(row, bindingWork) { mutableStateOf(bindingWork?.novelId("id")) }
    var candidates by remember(row) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pending by remember(row) { mutableStateOf(NovelIntakeAction()) }
    var tocQuery by remember(row) { mutableStateOf("") }
    var descending by remember(row) { mutableStateOf(false) }
    fun load(toc: Boolean) = task.run {
        if (supported == null) supported = repository.sourceSearch.features(connection)
        preview = mergeNovelPreview(preview, repository.sourceSearch.preview(connection, row, toc))
    }
    LaunchedEffect(row) { load(false) }
    val detail = novelSourceDetail(row, preview)
    val workId = chosenWork ?: receipt?.novelId("work_id") ?: preview?.novelId("work_id") ?: row.novelId("library_work_id")
    val toc = preview?.optJSONObject("toc")
    val readable = readableNovelSource(detail)
    val canCollect = supported != null && (bindingWork == null || supported?.intakeReuse == true || readable)
    fun intake(action: NovelIntakeAction, target: String? = chosenWork, createNew: Boolean = false, retryDirectory: Boolean = false) {
        pending = action
        task.run {
            try {
                if (receipt == null || retryDirectory) {
                    receipt = repository.sourceSearch.collect(connection, detail, target, createNew, requireNotNull(supported))
                    chosenWork = receipt!!.getString("work_id")
                    version = null
                }
                candidates = emptyList()
                val collected = requireNotNull(receipt)
                task.message = "资料已收录" + if (collected.novelId("job_id") != null) "，正在获取目录；失败仍保留资料" else ""
                if (collected.novelId("job_id") != null && version == null) version = repository.sourceSearch.collectionVersion(connection, collected)
                if (action.read) {
                    val fixedVersion = requireNotNull(version) { "此来源暂无可读版本" }
                    val opening = if (action.chapterUrl != null) {
                        require(supported?.chapterOpen == true) { "此服务尚不支持按预览章节打开" }
                        repository.sourceSearch.openChapter(connection, collected, fixedVersion, action.chapterUrl)
                    } else NovelSourceOpening(collected.getString("work_id"), fixedVersion)
                    onRead(opening)
                } else {
                    task.message = if (version != null) "目录已获取，可选择此来源阅读" else "资料已收录"
                    if (bindingWork != null) onBound()
                }
            } catch (e: NovelApiException) {
                if (e.code != "choose_existing_work" || bindingWork != null) throw e
                candidates = e.detail.novelObjects("candidates")
                require(candidates.isNotEmpty()) { "作品身份待确认，服务未返回候选，请刷新重试" }
                task.message = "库内有同名作品，请选择要关联的作品，或明确单独收录。"
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(detail.optString("name"), style = MaterialTheme.typography.headlineSmall)
                Text(detail.optString("provider_name"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss) { Text("返回结果") }
                NovelTaskStatus(task)
                preview?.novelId("message")?.let { Text(it) }; preview?.novelId("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (detail.optString("author").isNotBlank()) TextButton(onClick = { onAuthor(detail.optString("author")) }) { Text("作者：${detail.optString("author")} · 继续搜索") }
                if (bindingWork != null) Text("关联到《${bindingWork.optString("title")}》，保留原版本、书架与进度。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !task.busy && receipt == null && canCollect && candidates.isEmpty(), onClick = { intake(NovelIntakeAction()) }) {
                        Text(if (receipt != null) "已收录" else if (bindingWork != null) "关联当前作品" else "收录作品")
                    }
                    if (readable) TextButton(enabled = !task.busy && canCollect && candidates.isEmpty(), onClick = { intake(NovelIntakeAction(read = true)) }) {
                        Text(if (bindingWork != null) "使用此来源继续阅读" else "阅读此来源")
                    }
                    if (workId != null) TextButton(onClick = { onWork(workId) }) { Text("打开已收录作品") }
                    if (receipt?.novelId("job_id") != null && version == null && !task.busy) {
                        TextButton(onClick = { intake(pending) }) { Text("继续查看目录任务") }
                        TextButton(onClick = { intake(pending, retryDirectory = true) }) { Text("重新获取目录") }
                    }
                    TextButton(onClick = { try { openNovelWeb(context, row.getString("bookUrl")) } catch (e: Exception) { task.error = e.message } }) { Text("源网页") }
                }
                if (candidates.isNotEmpty()) {
                    Text("选择已有作品", style = MaterialTheme.typography.titleMedium)
                    candidates.forEach { candidate ->
                        TextButton(enabled = !task.busy, onClick = { intake(pending, target = candidate.getString("id")) }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("关联《${candidate.optString("title")}》 · ${candidate.optString("author").ifBlank { "作者待确认" }}")
                                Text(candidate.optString("description"), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    TextButton(enabled = !task.busy && supported?.intakeReuse == true, onClick = { intake(pending, target = null, createNew = true) }) { Text("作为独立作品收录") }
                }
                if (!canCollect && supported != null) Text("当前服务不支持把纯资料来源关联到已有作品，请更新服务后再试。")
                Text(if (readable) "收录后由服务获取目录；阅读此来源沿用版本定位与进度冲突规则。" else "此来源只收录资料，不提供可读正文。", style = MaterialTheme.typography.bodySmall)
                Text(detail.optString("intro").ifBlank { "暂无简介" })
                listOf("kind" to "分类", "category" to "类别", "word_count" to "字数", "latest_chapter" to "最新章节", "source_updated_at" to "来源更新", "status" to "状态").forEach { (key, title) ->
                    detail.novelId(key)?.let { Text("$title：$it", style = MaterialTheme.typography.bodyMedium) }
                }
                FlowRow {
                    TextButton(enabled = !task.busy, onClick = { load(false) }) { Text("重试详情") }
                    if (detail.novelStrings("capabilities").containsAll(listOf("detail", "toc"))) TextButton(enabled = !task.busy, onClick = { load(true) }) { Text(if (toc == null) "预览目录" else "刷新目录预览") }
                }
                Text("查看预览不收录、不下载正文，也不改阅读进度。点击可读章节会先收录或关联，再打开该章。", style = MaterialTheme.typography.bodySmall)
                if (readable && supported?.chapterOpen == false) Text("当前服务暂不支持点击预览章节；可收录后从作品目录选章。", style = MaterialTheme.typography.bodySmall)
                preview?.novelId("toc_error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (toc != null) {
                item {
                    Text(if (toc.optBoolean("complete")) "完整目录预览 · ${toc.novelObjects("items").size} 章" else "部分目录 · ${toc.optInt("pages")} 页 / ${toc.novelObjects("items").size} 章", style = MaterialTheme.typography.titleMedium)
                    if (!toc.optBoolean("complete")) Text("最多预览 3 页、200 章，限时 20 秒；以下筛选只覆盖已预览部分。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(tocQuery, { tocQuery = it }, Modifier.fillMaxWidth(), label = { Text("过滤预览标题") }, singleLine = true)
                    FilterChip(descending, { descending = !descending }, label = { Text(if (descending) "倒序" else "正序") })
                }
                items(novelPreviewChapters(preview, tocQuery, descending)) { chapter ->
                    val url = chapter.novelId("chapter_url")
                    if (readable && supported?.chapterOpen == true && url != null) {
                        TextButton(enabled = !task.busy && canCollect && candidates.isEmpty(), onClick = { intake(NovelIntakeAction(read = true, chapterUrl = url)) }) { Text(chapter.optString("title")) }
                    } else Text(chapter.optString("title"))
                }
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
