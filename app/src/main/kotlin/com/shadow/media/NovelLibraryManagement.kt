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
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun NovelBulkBooks(repository: NovelRepository, c: CatalogConnection, onWork: (String) -> Unit) {
    val task = rememberNovelTask()
    var query by remember { mutableStateOf("") }; var submitted by remember { mutableStateOf("") }
    var shelf by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var ids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var total by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf("shelf_add") }; var value by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("want") }
    suspend fun load(more: Boolean = false) {
        val result = repository.live(c, listOf("works"), listOf("view" to if (shelf) "shelf" else "search", "q" to submitted, "limit" to "50", "offset" to if (more) rows.size.toString() else "0"))
        rows = (if (more) rows else emptyList()) + result.novelObjects("items"); total = result.optInt("total")
    }
    LaunchedEffect(shelf) { task.run { load() } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("批量整理作品", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
            NovelSearchField(query, { query = it }, !task.busy, { submitted = query.trim(); task.run { load() } }, "按书名或作者查找")
            FlowRow { FilterChip(shelf, { shelf = !shelf }, label = { Text(if (shelf) "当前书架" else "全部作品") })
                TextButton(enabled = !task.busy, onClick = { ids = (ids + rows.map { it.getString("id") }).take(500).toSet() }) { Text("选择已加载") }
                TextButton(onClick = { ids = emptySet() }) { Text("清空选择") }
            }
            Text("已选 ${ids.size} 本，跨页保留，最多 500 本。移出书架保留作品与原文件。", style = MaterialTheme.typography.bodySmall)
            NovelOptionChips("操作", action, listOf("shelf_add" to "加入书架", "shelf_remove" to "移出书架", "tags_add" to "添加标签", "tags_remove" to "移除标签", "group" to "主分组", "status" to "阅读状态", "refresh" to "更新目录")) { action = it }
            if (action in setOf("tags_add", "tags_remove", "group")) OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(if (action == "group") "主分组（留空解除）" else "标签（逗号分隔）") })
            if (action == "status") NovelOptionChips("阅读状态", status, listOf("want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停")) { status = it }
            Button(enabled = !task.busy && ids.isNotEmpty(), onClick = { task.run {
                val result = repository.tools.bulkBooks(c, ids, action, if (action == "status") status else value, novelTags(value))
                task.message = novelReceipt(result) + if (action == "refresh") " · 目录任务已排队" else ""
                load()
            } }) { Text("应用到 ${ids.size} 本作品") }
        }
        items(rows, key = { it.getString("id") }) { row -> Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row { val id = row.getString("id"); Checkbox(id in ids, { checked -> if (!checked) ids = ids - id else if (ids.size < 500) ids = ids + id })
                Column(Modifier.weight(1f)) { Text(row.optString("title")); Text(row.optString("author"), style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { onWork(row.getString("id")) }) { Text("作品详情") }
        } } }
        if (rows.size < total) item { TextButton(enabled = !task.busy, onClick = { task.run { load(true) } }) { Text("加载更多 · ${rows.size} / $total") } }
    }
}

@Composable internal fun NovelSubscriptions(repository: NovelRepository, c: CatalogConnection, onExport: (NovelExport) -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val task = rememberNovelTask()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    var articleSource by remember { mutableStateOf<String?>(null) }
    var onlyUnread by remember { mutableStateOf(false) }; var onlyStarred by remember { mutableStateOf(false) }
    var jobId by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() { rows = repository.live(c, listOf("subscriptions")).novelObjects("items") }
    LaunchedEffect(Unit) { task.run { refresh() } }
    val source = rows.firstOrNull { it.optString("id") == articleSource }
    androidx.activity.compose.BackHandler(articleSource != null) { articleSource = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("订阅与 RSS", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
            Text("支持书源包和标准 RSS/Atom。网页发现使用书源栏目；不执行自定义 RSS 网页规则。", style = MaterialTheme.typography.bodySmall)
            FlowRow { Button(enabled = !task.busy, onClick = { editing = JSONObject() }) { Text("添加订阅") }
                TextButton(enabled = !task.busy, onClick = { task.run { refresh() } }) { Text("刷新列表") }
                TextButton(enabled = !task.busy, onClick = onImport) { Text("导入 JSON / OPML") }
                TextButton(enabled = !task.busy, onClick = { onExport(NovelExport("novel-subscriptions.json", listOf("subscriptions", "export"))) }) { Text("导出 JSON") }
                TextButton(enabled = !task.busy, onClick = { onExport(NovelExport("novel-rss.opml", emptyList(), opml = true)) }) { Text("导出 RSS OPML") }
            }
            jobId?.let { id -> TextButton(enabled = !task.busy, onClick = { task.run { task.progress = repository.live(c, listOf("jobs", id)); task.message = novelJobSummary(task.progress!!); refresh() } }) { Text("刷新订阅任务状态") } }
        }
        if (articleSource == null) items(rows, key = { it.getString("id") }) { row -> Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.optString("name"), style = MaterialTheme.typography.titleMedium)
            Text("${if (row.optString("kind") == "rss") "RSS / Atom" else "书源包"} · ${if (row.optBoolean("enabled")) "启用" else "停用"} · ${if (row.optInt("interval_hours") == 0) "手动刷新" else "每 ${row.optInt("interval_hours")} 小时"}", style = MaterialTheme.typography.bodySmall)
            if (row.optDouble("last_success", 0.0) > 0) Text("上次成功：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date((row.optDouble("last_success") * 1000).toLong()))}", style = MaterialTheme.typography.bodySmall)
            row.novelId("error")?.let { Text(it, color = MaterialTheme.colorScheme.error); Text("保留上次成功内容与收藏状态", style = MaterialTheme.typography.bodySmall) }
            row.optJSONObject("result")?.let { Text(novelReceipt(it), style = MaterialTheme.typography.bodySmall) }
            FlowRow {
                if (row.optString("kind") == "rss") TextButton(onClick = { articleSource = row.getString("id") }) { Text("文章 ${row.novelObjects("entries").size}") }
                TextButton(enabled = !task.busy && row.optBoolean("enabled"), onClick = { task.run {
                    val receipt = repository.mutate(c, listOf("subscriptions", row.getString("id"), "refresh"), "POST")
                    jobId = receipt.getString("job_id")
                    try { repository.sourceSearch.awaitJob(c, jobId!!) { task.progress = it } }
                    finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) refresh() }
                    task.message = "订阅刷新完成"
                } }) { Text("获取更新") }
                TextButton(enabled = !task.busy, onClick = { editing = row }) { Text("编辑 / 启停") }
                TextButton(enabled = !task.busy, onClick = { removing = row }) { Text("移除") }
            }
        } } }
        else {
            item { TextButton(onClick = { articleSource = null }) { Text("返回订阅列表") }
                Text(source?.optString("name").orEmpty(), style = MaterialTheme.typography.titleMedium)
                FlowRow { FilterChip(onlyUnread, { onlyUnread = !onlyUnread }, label = { Text("只看未读") }); FilterChip(onlyStarred, { onlyStarred = !onlyStarred }, label = { Text("只看收藏") }) }
            }
            items(source?.novelObjects("entries").orEmpty().filter { (!onlyUnread || !it.optBoolean("read")) && (!onlyStarred || it.optBoolean("starred")) }, key = { it.getString("id") }) { article -> Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(article.optString("title"), style = MaterialTheme.typography.titleMedium)
                    Text(article.optString("published"), style = MaterialTheme.typography.bodySmall)
                    Text(article.optString("summary"))
                    FlowRow {
                        TextButton(onClick = { try { openNovelWeb(context, article.getString("url")) } catch (e: Exception) { task.error = e.message } }) { Text("打开原文") }
                        listOf("read" to if (article.optBoolean("read")) "标为未读" else "标为已读", "starred" to if (article.optBoolean("starred")) "取消收藏" else "收藏").forEach { (key, label) ->
                            TextButton(enabled = !task.busy, onClick = { task.run { repository.mutate(c, listOf("subscriptions", articleSource!!, "article"), "PATCH", JSONObject().put("entry_id", article.getString("id")).put(key, !article.optBoolean(key))); refresh() } }) { Text(label) }
                        }
                    }
                }
            } }
        }
    }
    editing?.let { value -> NovelSubscriptionEditor(repository, c, value, onDismiss = { editing = null }, onSaved = { editing = null; task.run { refresh() } }) }
    removing?.let { row -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除“${row.optString("name")}”？") }, text = { Text("移除订阅及其文章记录；已经导入的书源和作品保留。") }, confirmButton = {
        TextButton(onClick = { removing = null; task.run { repository.mutate(c, listOf("subscriptions", row.getString("id")), "DELETE"); refresh() } }) { Text("移除") }
    }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

@Composable private fun NovelSubscriptionEditor(repository: NovelRepository, c: CatalogConnection, row: JSONObject, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val task = rememberNovelTask()
    var name by remember { mutableStateOf(row.optString("name")) }; var url by remember { mutableStateOf(row.optString("url")) }
    var kind by remember { mutableStateOf(row.optString("kind", "rss")) }; var enabled by remember { mutableStateOf(row.optBoolean("enabled", true)) }
    var interval by remember { mutableStateOf(row.optInt("interval_hours").toString()) }
    var groups by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var ids by remember { mutableStateOf(row.novelStrings("group_ids").toSet()) }
    LaunchedEffect(Unit) { task.run { groups = repository.live(c, listOf("source-groups")).novelObjects("items") } }
    ModalBottomSheet(onDismissRequest = onDismiss) { LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("${if (row.novelId("id") == null) "添加" else "编辑"}订阅", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
            if (row.novelId("id") == null) NovelOptionChips("类型", kind, listOf("rss" to "RSS / Atom", "sources" to "书源包")) { kind = it }
            OutlinedTextField(name, { name = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("名称") })
            OutlinedTextField(url, { url = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("订阅 URL") })
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("刷新间隔（0 手动，1–720 小时）") })
            FilterChip(enabled, { enabled = !enabled }, label = { Text("启用订阅") })
            Text("书源分组")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { groups.forEach { group -> val id = group.getString("id")
                FilterChip(id in ids, { ids = if (id in ids) ids - id else ids + id }, label = { Text(group.optString("name")) })
            } }
            Button(enabled = !task.busy && name.isNotBlank() && url.isNotBlank() && interval.toIntOrNull() in 0..720, onClick = { task.run {
                val id = row.novelId("id")
                repository.mutate(c, listOf("subscriptions") + listOfNotNull(id), if (id == null) "POST" else "PUT", JSONObject()
                    .put("name", name.trim()).put("url", url.trim()).put("kind", kind).put("enabled", enabled).put("interval_hours", interval.toInt()).put("group_ids", JSONArray(ids)))
                onSaved()
            } }) { Text("保存订阅") }
        }
    } }
}
