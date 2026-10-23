@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

internal data class NovelExport(val name: String, val path: List<String>, val body: JSONObject? = null, val opml: Boolean = false)

@Composable internal fun NovelToolsScreen(repository: NovelRepository, connection: CatalogConnection, onWork: (String) -> Unit, initialPage: String = "", onSearch: (String) -> Unit = {}) {
    val context = LocalContext.current
    val task = rememberNovelTask()
    var page by remember { mutableStateOf(initialPage) }
    var export by remember { mutableStateOf<NovelExport?>(null) }
    var importPath by remember { mutableStateOf<List<String>>(emptyList()) }
    var importBook by remember { mutableStateOf(false) }
    var backupId by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var url by remember { mutableStateOf("") }
    var filename by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = export
        if (uri != null && request != null) task.run {
            val output = { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入所选文件" } }
            if (request.opml) repository.tools.exportOpml(connection, output) else repository.tools.export(connection, request.path, output, request.body)
            task.message = "已导出 ${request.name}"
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task.run {
            val input = { requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件" } }
            val result = if (importBook) {
                val name = withContext(Dispatchers.IO) { novelDocumentName(context, uri) }
                repository.tools.upload(connection, name, input)
            } else repository.tools.import(connection, importPath, input)
            task.message = "导入完成：${novelReceipt(result)}"
            result.novelId("work_id")?.let(onWork)
        }
    }
    fun exportFile(request: NovelExport) { export = request; save.launch(request.name) }
    fun importFile(path: List<String>, book: Boolean = false) { importPath = path; importBook = book; open.launch(if (book) arrayOf("text/plain", "application/epub+zip") else arrayOf("application/json", "text/xml", "application/xml", "text/x-opml", "application/octet-stream")) }
    LaunchedEffect(page) {
        task.cancel(); task.error = null; task.message = null
        if (page.isEmpty()) task.run { summary = repository.live(connection, listOf("library", "summary")) }
    }
    BackHandler(page.isNotEmpty()) { page = "" }
    Column(Modifier.fillMaxSize()) {
        if (page.isNotEmpty()) TextButton(onClick = { page = "" }) { Text("‹ 返回书库工具") }
        Column(Modifier.padding(horizontal = 20.dp)) { NovelTaskStatus(task) }
        when (page) {
            "sources" -> NovelSourceManager(repository, connection, { exportFile(it) }, { importFile(listOf("providers", "import")) })
            "groups" -> NovelSourceGroups(repository, connection)
            "books" -> NovelBulkBooks(repository, connection, onWork)
            "subscriptions" -> NovelSubscriptions(repository, connection, { exportFile(it) }, { importFile(listOf("subscriptions", "import")) })
            "search" -> NovelSearchSettings(repository, connection, onDismiss = { page = "" }, onSearch = { page = ""; onSearch(it) })
            else -> LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (page.isEmpty()) {
                    item {
                        Text("书库工具", style = MaterialTheme.typography.headlineSmall)
                        summary?.let { Text("作品 ${it.optInt("works")} · 书架 ${it.optInt("on_shelf")} · 有阅读记录 ${it.optInt("read_works")}\n追更 ${it.optInt("following")} · 笔记 ${it.optInt("notes")}") }
                        if (summary == null) TextButton(enabled = !task.busy, onClick = { task.run { summary = repository.live(connection, listOf("library", "summary")) } }) { Text("加载统计") }
                    }
                    items(listOf("sources" to "书源管理", "groups" to "独立书源分组", "books" to "批量整理作品", "subscriptions" to "订阅与 RSS", "files" to "文件与书目清单", "settings" to "偏好、缓存与备份")) { (id, name) ->
                        Card(onClick = { page = id }) { Text(name, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.titleMedium) }
                    }
                }
                if (page == "files") {
                    item { Text("文件与书目清单", style = MaterialTheme.typography.titleLarge)
                        Text("上传 TXT/EPUB 到当前 Novel 书库；本机阅读仍可使用阅读页原有的文件导入。", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !task.busy, onClick = { importFile(emptyList(), true) }) { Text("选择 TXT / EPUB 上传") }
                        Text("客户端上限 64 MiB，流式上传；服务器限制更小时会显示错误。", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("公开 TXT/EPUB 文件 URL") })
                        OutlinedTextField(filename, { filename = it }, Modifier.fillMaxWidth(), label = { Text("保存文件名（含 .txt 或 .epub）") })
                        Button(enabled = !task.busy && url.isNotBlank() && filename.isNotBlank(), onClick = { task.run {
                            val result = repository.mutate(connection, listOf("library", "import-url"), "POST", JSONObject().put("url", url.trim()).put("filename", filename.trim()))
                            activeJob = result.getString("job_id"); task.message = "文件导入已排队"
                            val completed = repository.sourceSearch.awaitJob(connection, activeJob!!) { task.progress = it }
                            task.message = "文件导入完成：${novelReceipt(completed.optJSONObject("result") ?: JSONObject())}"
                        } }) { Text("从公开地址导入") }
                        activeJob?.let { id -> TextButton(enabled = !task.busy, onClick = { task.run { task.progress = repository.live(connection, listOf("jobs", id)); task.message = novelJobSummary(task.progress!!) } }) { Text("查看导入任务") } }
                    }
                    item { HorizontalDivider(); Text("书目清单包含作品资料、书架和书单。重复导入增量合并，保留已有个人设置；不含正文与完整阅读进度。")
                        FlowRow { TextButton(enabled = !task.busy, onClick = { exportFile(NovelExport("novel-library.json", listOf("library", "books", "export"))) }) { Text("导出书目清单") }
                            TextButton(enabled = !task.busy, onClick = { importFile(listOf("library", "books", "import")) }) { Text("导入书目清单") }
                        }
                    }
                }
                if (page == "settings") {
                    item {
                        val prefs = context.getSharedPreferences("novel_library_ui", Context.MODE_PRIVATE)
                        var defaultPage by remember { mutableStateOf(prefs.getString("default:${connection.id}", "home") ?: "home") }
                        NovelOptionChips("进入此连接时打开", defaultPage, listOf("home" to "书城", "shelf" to "书架", "sources" to "在线搜索", "offline" to "离线")) {
                            defaultPage = it; prefs.edit { putString("default:${connection.id}", it) }
                        }
                        TextButton(onClick = { page = "search" }) { Text("搜索历史与排除词") }
                        TextButton(enabled = !task.busy, onClick = { task.run { val result = repository.mutate(connection, listOf("library", "cache", "clear"), "POST"); task.message = "已清理 ${result.optInt("cleared")} 条服务器查询缓存" } }) { Text("清理服务器查询缓存") }
                    }
                    item {
                        Text("下载服务端备份", style = MaterialTheme.typography.titleMedium)
                        Text("包含数据库与受管文件；外部挂载原书库、服务配置和凭据不在备份中。完整恢复仅能在服务器空目录执行。", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !task.busy, onClick = { task.run {
                            val receipt = repository.mutate(connection, listOf("library", "backups"), "POST")
                            task.progress = null; backupId = receipt.getString("job_id")
                            val completed = repository.sourceSearch.awaitJob(connection, backupId!!) { task.progress = it }
                            task.message = novelJobSummary(completed)
                        } }) { Text("生成备份") }
                        backupId?.let { id -> FlowRow {
                            TextButton(enabled = !task.busy, onClick = { task.run { task.progress = repository.live(connection, listOf("jobs", id)); task.message = novelJobSummary(task.progress!!) } }) { Text("刷新备份状态") }
                            TextButton(enabled = !task.busy && task.progress?.optString("state") == "succeeded", onClick = { exportFile(NovelExport("novel-library-backup.tar.gz", listOf("library", "backups", id, "download"))) }) { Text("保存备份文件") }
                        } }
                    }
                }
            }
        }
    }
}

internal fun novelDocumentName(context: Context, uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
    if (it.moveToFirst()) it.getString(0) else null
} ?: error("文件未提供名称，请从系统文件选择器选择 TXT 或 EPUB")
internal fun novelReceipt(value: JSONObject): String = listOf("created" to "新增", "existing_preserved" to "保留已有", "added_lists" to "新增书单", "changed" to "修改", "unchanged" to "未变", "skipped" to "跳过").mapNotNull { (key, label) ->
    if (value.has(key)) "$label ${value.optInt(key)}" else null
}.joinToString(" · ").ifBlank { "已保存" }
internal fun novelJobSummary(job: JSONObject) = when (job.optString("state")) {
    "succeeded" -> "任务已完成"; "failed" -> "任务失败：${job.optString("error")}"; "cancelled" -> "任务已取消"; else -> "任务执行中，可稍后刷新"
}

@Composable internal fun NovelSourceGroups(repository: NovelRepository, c: CatalogConnection) {
    val task = rememberNovelTask()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    suspend fun refresh() { rows = repository.live(c, listOf("source-groups")).novelObjects("items") }
    LaunchedEffect(Unit) { task.run { refresh() } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("独立书源分组", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
            Text("一个书源可属于多个组。移除分组仅解除成员归属，书源仍保留。")
            OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text(if (editing == null) "新分组名称" else "重命名分组") })
            FlowRow { Button(enabled = !task.busy && name.isNotBlank(), onClick = { task.run {
                repository.mutate(c, listOf("source-groups") + listOfNotNull(editing), if (editing == null) "POST" else "PATCH", JSONObject().put("name", name.trim()))
                name = ""; editing = null; refresh()
            } }) { Text("保存分组") }
                TextButton(enabled = !task.busy, onClick = { task.run { refresh() } }) { Text("刷新") }
                if (editing != null) TextButton(onClick = { editing = null; name = "" }) { Text("取消编辑") }
            }
        }
        items(rows, key = { it.getString("id") }) { row -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(row.optString("name"), style = MaterialTheme.typography.titleMedium)
            Text("${row.optInt("count")} 个成员", style = MaterialTheme.typography.bodySmall)
            FlowRow { TextButton(enabled = !task.busy, onClick = { editing = row.getString("id"); name = row.getString("name") }) { Text("重命名") }
                TextButton(enabled = !task.busy, onClick = { removing = row }) { Text("移除分组") }
            }
        } } }
    }
    removing?.let { row -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除“${row.optString("name")}”？") }, text = { Text("解除该组的书源归属，书源与其他分组保留。") }, confirmButton = {
        TextButton(onClick = { removing = null; task.run { repository.mutate(c, listOf("source-groups", row.getString("id")), "DELETE"); refresh() } }) { Text("移除") }
    }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

@Composable internal fun NovelSourceManager(repository: NovelRepository, c: CatalogConnection, onExport: (NovelExport) -> Unit, onImport: () -> Unit) {
    val task = rememberNovelTask()
    var filter by remember { mutableStateOf(NovelSourceFilter()) }
    var ids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var choosing by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var addGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var removeGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addTags by remember { mutableStateOf("") }; var removeTags by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("enable") }; var query by remember { mutableStateOf("") }
    var recipe by remember { mutableStateOf<String?>(null) }
    var currentJob by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { task.run { groups = repository.live(c, listOf("source-groups")).novelObjects("items") } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("书源管理", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
            Button(onClick = { choosing = true }) { Text("筛选与选择 · ${ids.size} 源") }
            Text("选择跨页保留。启用不代表目录或正文已验证；需要登录的来源继续跳过。", style = MaterialTheme.typography.bodySmall)
            FlowRow { TextButton(enabled = ids.isNotEmpty() && !task.busy, onClick = { onExport(NovelExport("novel-sources.json", listOf("providers", "export"), JSONObject().put("provider_ids", JSONArray(ids)))) }) { Text("导出原生规则") }
                TextButton(enabled = !task.busy, onClick = onImport) { Text("导入规则文件") }
                TextButton(enabled = ids.size == 1 && !task.busy, onClick = { recipe = ids.single() }) { Text("编辑选中规则") }
            }
            Text("导出为 Novel 原生规则 JSON，不是 Legado 格式。规则仅由服务端执行。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(addTags, { addTags = it }, Modifier.fillMaxWidth(), label = { Text("添加标签（逗号分隔）") })
            OutlinedTextField(removeTags, { removeTags = it }, Modifier.fillMaxWidth(), label = { Text("移除标签（逗号分隔）") })
            Text("添加到分组")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { groups.forEach { group -> val id = group.getString("id")
                FilterChip(id in addGroups, { addGroups = if (id in addGroups) addGroups - id else addGroups + id; removeGroups = removeGroups - id }, label = { Text(group.optString("name")) })
            } }
            Text("移出分组")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { groups.forEach { group -> val id = group.getString("id")
                FilterChip(id in removeGroups, { removeGroups = if (id in removeGroups) removeGroups - id else removeGroups + id; addGroups = addGroups - id }, label = { Text(group.optString("name")) })
            } }
            Button(enabled = !task.busy && ids.isNotEmpty(), onClick = { task.run {
                val result = repository.mutate(c, listOf("providers", "bulk-organize"), "POST", JSONObject().put("provider_ids", JSONArray(ids))
                    .put("tags_add", JSONArray(novelTags(addTags))).put("tags_remove", JSONArray(novelTags(removeTags)))
                    .put("group_ids_add", JSONArray(addGroups)).put("group_ids_remove", JSONArray(removeGroups)))
                task.message = novelReceipt(result)
            } }) { Text("整理选中的 ${ids.size} 个书源") }
        }
        item { HorizontalDivider()
            val actions = listOf("enable" to "启用", "disable" to "停用", "explore_enable" to "开启发现", "explore_disable" to "关闭发现", "top" to "置顶", "bottom" to "置底", "test" to "搜索检查")
            NovelOptionChips("批量操作", action, actions) { action = it }
            if (action == "test") OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("检查关键词（最多 200 源）") })
            Button(enabled = !task.busy && ids.isNotEmpty() && (action != "test" || ids.size <= 200 && query.isNotBlank()), onClick = { task.run {
                val result = repository.mutate(c, listOf("providers", "bulk-action"), "POST", JSONObject().put("provider_ids", JSONArray(ids)).put("action", action).put("q", query.ifBlank { "山海" }))
                currentJob = result.novelId("job_id"); task.message = novelReceipt(result)
                currentJob?.let { repository.sourceSearch.awaitJob(c, it) { job -> task.progress = job }; task.message = "检查完成" }
            } }) { Text("${actions.first { it.first == action }.second} · ${ids.size} 源") }
            if (ids.size > 200 && action == "test") Text("请缩小到 200 源以内")
            currentJob?.let { id -> FlowRow {
                TextButton(enabled = !task.busy, onClick = { task.run { task.progress = repository.live(c, listOf("jobs", id)); task.message = novelJobSummary(task.progress!!) } }) { Text("刷新任务") }
                TextButton(onClick = { task.cancel(); task.run { task.progress = repository.sourceSearch.cancel(c, id); task.message = "已请求取消" } }) { Text("取消检查") }
            } }
        }
        items(task.progress?.optJSONObject("result")?.novelObjects("sources").orEmpty()) { row -> Text("${row.optString("provider_id")} · ${if (row.optBoolean("skipped")) row.optString("message") else if (row.optBoolean("ok")) "搜索通过" else "搜索失败"}", style = MaterialTheme.typography.bodySmall) }
    }
    if (choosing) NovelSourcePicker(repository, c, filter, ids, { choosing = false }) { f, chosen -> filter = f; ids = chosen; choosing = false }
    recipe?.let { NovelRecipeEditor(repository, c, it) { recipe = null } }
}

@Composable internal fun NovelRecipeEditor(repository: NovelRepository, c: CatalogConnection, id: String, onDismiss: () -> Unit) {
    val task = rememberNovelTask()
    var text by remember { mutableStateOf("") }; var revision by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(id) { task.run { val result = repository.live(c, listOf("providers", id, "recipe")); text = result.getJSONObject("recipe").toString(2); revision = result.getInt("revision") } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("编辑原生规则 · 修订 $revision", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 8, maxLines = 16, label = { Text("规则 JSON") })
                Button(enabled = !task.busy && revision > 0, onClick = { task.run {
                    val result = repository.mutate(c, listOf("providers", id, "recipe"), "PUT", JSONObject().put("recipe", JSONObject(text)).put("expected_revision", revision))
                    revision = result.getInt("revision"); latest = null; task.message = "规则已保存；原体检结果需重新验证"
                } }) { Text("保存规则") }
                Text("冲突时保留输入。读取最新版本，核对并合并后再保存。", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !task.busy, onClick = { task.run { latest = repository.live(c, listOf("providers", id, "recipe")) } }) { Text("查看最新版本（保留输入）") }
            }
            latest?.let { value -> item {
                Text("服务器修订 ${value.optInt("revision")}")
                androidx.compose.foundation.text.selection.SelectionContainer { Text(value.getJSONObject("recipe").toString(2), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { revision = value.getInt("revision"); latest = null }) { Text("已合并输入，使用此修订号保存") }
            } }
        }
    }
}
