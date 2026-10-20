@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package top.cylunex.shadowmedia

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import top.cylunex.shadowmedia.library.*
import top.cylunex.shadowmedia.reading.ReaderActivity

private data class NovelRoute(val kind: String, val id: String = "", val name: String = "", val version: String = "")
private data class NovelEdit(val title: String, val fields: List<Pair<String, String>>, val save: suspend (Map<String, String>) -> Unit)

@Composable internal fun NovelScreen(repository: NovelRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<CatalogConnection>>(emptyList()) }
    var connection by remember { mutableStateOf<CatalogConnection?>(null) }
    var route by remember { mutableStateOf(NovelRoute("home")) }
    var stack by remember { mutableStateOf<List<NovelRoute>>(emptyList()) }
    var query by remember { mutableStateOf(NovelQuery()) }
    var search by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var navigation by remember { mutableStateOf(JSONObject()) }
    var offset by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var connectionEditor by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<CatalogConnection?>(null) }
    var filter by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<NovelEdit?>(null) }
    var memberships by remember { mutableStateOf<List<JSONObject>?>(null) }
    var membershipOffset by remember { mutableIntStateOf(0) }
    var membershipTotal by remember { mutableIntStateOf(0) }
    var deletedLists by remember { mutableStateOf(false) }
    var disconnectedDownloads by remember { mutableStateOf<List<top.cylunex.shadowmedia.database.LibraryAssetEntity>>(emptyList()) }
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        job = scope.launch {
            busy = true; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Novel 操作失败，请重试" }
            finally { busy = false }
        }
    }
    suspend fun load(next: Boolean = false) {
        val c = connection ?: return
        if (route.kind == "offline") { disconnectedDownloads = repository.downloaded(novelScope(c)); data = JSONObject(); return }
        val start = if (next) offset + PAGE else 0
        val pagination = listOf("offset" to start.toString(), "limit" to PAGE.toString())
        val result = when (route.kind) {
            "home" -> repository.get(c, listOf("city", "home"))
            "works" -> repository.get(c, listOf("works"), query.copy(view = route.id).parameters() + pagination)
            "authors" -> repository.get(c, listOf("authors"), listOf("q" to search) + pagination)
            "author" -> repository.get(c, listOf("authors", "detail"), listOf("name" to route.name, "q" to search, "sort" to query.sort, "serial_status" to query.serialStatus) + pagination)
            "lists" -> repository.get(c, listOf("booklists"), listOf("q" to search, "deleted" to deletedLists.toString()) + pagination)
            "list" -> repository.get(c, listOf("booklists", route.id), listOf("q" to search) + pagination)
            "work" -> repository.get(c, listOf("works", route.id))
            "contents" -> repository.get(c, listOf("works", route.id, "contents"), listOf("version_id" to route.version) + pagination)
            else -> error("未知 Novel 页面")
        }
        currentCoroutineContext().ensureActive()
        if (next) {
            val combined = JSONArray()
            (data?.novelObjects("items").orEmpty() + result.novelObjects("items")).forEach(combined::put)
            result.put("items", combined)
        }
        data = result; offset = start
        if (route.kind == "works") navigation = repository.get(c, listOf("navigation"), query.copy(view = route.id).parameters())
    }
    fun navigate(target: NovelRoute) { if (busy) return; stack = stack + route; route = target; search = ""; data = null }
    fun back() {
        job?.cancel(); busy = false; error = null; message = null
        if (stack.isNotEmpty()) { route = stack.last(); stack = stack.dropLast(1); data = null; search = "" }
        else if (connection != null) { connection = null; data = null }
        else onBack()
    }
    fun open(work: String, version: String? = null, chapter: String? = null) = execute {
        val prepared = repository.prepare(requireNotNull(connection), work, version, chapter)
        context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("assetId", prepared.asset.id).putExtra("novelLocator", prepared.locator))
    }
    suspend fun change(path: List<String>, method: String, body: JSONObject? = null) {
        repository.mutate(requireNotNull(connection), path, method, body)
        load(); message = "已保存"
    }
    LaunchedEffect(Unit) { try { connections = repository.store.load() } catch (e: Exception) { error = e.message } }
    LaunchedEffect(connection?.id, route, query, deletedLists) {
        if (connection != null) { while (busy) delay(40); execute { load() } }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, connection, route) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && connection != null) { NovelRepository.schedule(context); if (!busy) execute { load() } } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = ::back)
    val c = connection
    val editable = data?.has("_cached_at") != true && !LibraryResources.offlineOnly
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text(if (c == null) "Shadow Novel" else when (route.kind) {
            "work" -> data?.optString("title") ?: "作品"; "author" -> route.name; "list" -> data?.optString("title") ?: "书单"; "contents" -> "版本目录"; else -> c.name
        }, maxLines = 1) }, navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
            if (c == null) IconButton(onClick = { editingConnection = null; connectionEditor = true }) { Icon(Icons.Rounded.Add, "添加 Novel") }
            else IconButton(enabled = !busy, onClick = { execute { load() } }) { Icon(Icons.Rounded.Refresh, "刷新") }
        })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary) }
        if (c == null) LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("原生浏览书城、书单和作家，使用 Readium 阅读。输入你自己的 Novel 服务根地址；凭据加密保存在本机。") }
            items(connections, key = { it.id }) { item -> Card {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { execute { repository.discover(item); connection = item; route = NovelRoute("home"); stack = emptyList(); NovelRepository.schedule(context) } }, modifier = Modifier.weight(1f)) { Text(item.name) }
                    TextButton(enabled = !busy, onClick = { connection = item; route = NovelRoute("offline"); stack = emptyList() }) { Text("离线") }
                    IconButton(onClick = { editingConnection = item; connectionEditor = true }) { Icon(Icons.Rounded.Edit, "编辑连接") }
                    IconButton(onClick = { try { repository.store.remove(item.id); connections = repository.store.load(); message = "连接已移除；下载与待同步进度保留在本机阅读书架" } catch (e: Exception) { error = e.message } }) { Icon(Icons.Rounded.LinkOff, "移除连接") }
                }
            } }
        } else {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("书城" to NovelRoute("home"), "全部" to NovelRoute("works", "city"), "搜索" to NovelRoute("works", "search"), "热度" to NovelRoute("works", "city"), "作家" to NovelRoute("authors"), "书单" to NovelRoute("lists"), "书架" to NovelRoute("works", "shelf"), "历史" to NovelRoute("works", "history"), "离线" to NovelRoute("offline")).forEach { (title, target) ->
                    AssistChip(enabled = !busy, onClick = { stack = emptyList(); search = ""; if (title == "热度") query = query.copy(sort = "popularity"); if (route == target) execute { load() } else { route = target; data = null } }, label = { Text(title) })
                }
            }
            if (data?.has("_cached_at") == true) Text("本机缓存 · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(data!!.getLong("_cached_at")))}", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            if (route.kind in setOf("works", "authors", "author", "lists", "list")) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, { search = it }, Modifier.weight(1f), placeholder = { Text("搜索服务端全部内容") }, singleLine = true)
                TextButton(enabled = !busy, onClick = { if (route.kind == "works" && query.q != search) query = query.copy(q = search) else execute { load() } }) { Text("搜索") }
                if (route.kind == "works") IconButton(onClick = { filter = true }) { Icon(Icons.Rounded.FilterList, "筛选与排序") }
            }
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (route.kind == "offline") {
                    item { Text("已下载的章节窗口可离线阅读；不代表已下载全书。", style = MaterialTheme.typography.bodySmall) }
                    items(disconnectedDownloads, key = { it.id }) { asset -> Card(onClick = { context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("assetId", asset.id)) }) { Text(asset.title, Modifier.fillMaxWidth().padding(20.dp)) } }
                }
                if (route.kind == "home") data?.novelObjects("sections")?.forEach { section ->
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(enabled = !busy, onClick = {
                            val id = section.optString("id")
                            query = NovelQuery(sort = if (id == "popular") "popularity" else "recent", serialStatus = if (id == "completed") "completed" else "",
                                availability = if (id in setOf("completed", "shuffle")) "readable" else "", categories = if (id.startsWith("category-")) listOf(id.removePrefix("category-")) else emptyList())
                            navigate(NovelRoute("works", "city"))
                        }) { Text("全部 ${section.optInt("total")}") }
                    } }
                    items(section.novelObjects("items"), key = { section.optString("id") + it.getString("id") }) { work -> NovelWorkCard(work, enabled = !busy) { navigate(NovelRoute("work", work.getString("id"))) } }
                }
                if (route.kind == "work") data?.let { work ->
                    item { Text(work.optString("author").ifBlank { "作者未知" }); Text(work.optString("description").ifBlank { "暂无简介" }, Modifier.padding(vertical = 12.dp))
                        Text(listOf(work.optString("category"), serialLabel(work.optString("serial_status")), work.novelStrings("genre_tags").joinToString(" · ")).filter(String::isNotBlank).joinToString(" · "))
                        if (work.optString("aliases").isNotBlank()) Text("别名：${work.optString("aliases")}", style = MaterialTheme.typography.bodySmall)
                        work.optJSONObject("reading_summary")?.let { Text("${it.optInt("versions")} 个版本 · 最多 ${it.optInt("chapters")} 章 · 本地约 ${it.optLong("local_characters")} 字", style = MaterialTheme.typography.bodySmall) }
                        work.optJSONObject("popularity")?.let { popularity ->
                            Text("热度 ${work.opt("popularity_score")} · ${popularity.optString("source")}\n原始${popularity.optString("metric")}：${popularity.optString("raw").ifBlank { popularity.opt("value")?.toString().orEmpty() }}", style = MaterialTheme.typography.bodySmall)
                            if (popularity.optDouble("observed_at", 0.0) > 0) Text("观测于 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date((popularity.optDouble("observed_at") * 1000).toLong()))}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row { Button(enabled = !busy, onClick = { open(route.id) }) { Text(if (work.optJSONObject("progress") != null) "继续阅读" else "开始阅读") }
                            if (!work.optBoolean("author_unknown") && work.optString("author").isNotBlank()) TextButton(enabled = !busy, onClick = { navigate(NovelRoute("author", name = work.getString("author"))) }) { Text("查看作家") } }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(work.optBoolean("on_shelf"), enabled = editable && !busy, onClick = { execute { change(listOf("me", "books", route.id), "PATCH", JSONObject().put("on_shelf", !work.optBoolean("on_shelf"))) } }, label = { Text(if (work.optBoolean("on_shelf")) "已在书架" else "加入书架") })
                            TextButton(enabled = editable && !busy, onClick = { execute { val result = repository.get(c, listOf("booklists"), listOf("work_id" to route.id, "limit" to PAGE.toString())); memberships = result.novelObjects("items"); membershipOffset = 0; membershipTotal = result.optInt("total") } }) { Text("加入书单") }
                            TextButton(enabled = editable && !busy, onClick = { editor = NovelEdit("个人阅读资料", listOf("分组" to work.optString("group_name"), "标签（逗号分隔）" to work.novelStrings("tags").joinToString(","))) { values -> change(listOf("me", "books", route.id), "PATCH", JSONObject().put("group_name", values.getValue("分组")).put("tags", JSONArray(splitTags(values.getValue("标签（逗号分隔）"))))) } }) { Text("分组与标签") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停").forEach { (value, title) ->
                            FilterChip(work.optString("reading_status") == value, enabled = editable && !busy, onClick = { execute { change(listOf("me", "books", route.id), "PATCH", JSONObject().put("reading_status", value)) } }, label = { Text(title) })
                        } }
                        if (!work.isNull("following_binding_id")) TextButton(enabled = editable && !busy, onClick = { execute { change(listOf("me", "books", route.id), "PATCH", JSONObject().put("following_binding_id", JSONObject.NULL)) } }) { Text("停止追更 · ${work.optInt("update_count")} 章更新") }
                    }
                    item { Text("版本与目录", style = MaterialTheme.typography.titleLarge) }
                    items(work.novelObjects("versions")) { version -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(version.optString("label")); Text("${version.optString("state")} · ${version.optString("format")}", style = MaterialTheme.typography.bodySmall)
                        FlowRow {
                            if (!version.isNull("id") && version.optString("id").isNotBlank()) {
                                TextButton(enabled = !busy, onClick = { navigate(NovelRoute("contents", route.id, version = version.getString("id"))) }) { Text("目录") }
                                TextButton(enabled = !busy, onClick = { open(route.id, version.getString("id")) }) { Text("阅读此版本") }
                            }
                            if (!version.isNull("binding_id") && version.optString("binding_id").isNotBlank()) {
                                TextButton(enabled = editable && !busy, onClick = { execute { change(listOf("me", "books", route.id), "PATCH", JSONObject().put("following_binding_id", version.getString("binding_id"))) } }) { Text("追更此来源") }
                                TextButton(enabled = editable && !busy, onClick = { execute { repository.mutate(c, listOf("bindings", version.getString("binding_id"), "refresh"), "POST"); message = "已提交目录检查，稍后刷新版本" } }) { Text("检查目录") }
                            }
                        }
                    } } }
                    if (work.novelObjects("versions").isEmpty()) item { Text("此作品尚无可读内容。资料保留在书城，可先加入书架。") }
                    item { Text("所属书单", style = MaterialTheme.typography.titleMedium) }
                    items(work.novelObjects("booklists")) { list -> TextButton(enabled = !busy, onClick = { navigate(NovelRoute("list", list.getString("id"))) }) { Text(list.optString("title")) } }
                    item { TextButton(enabled = editable && !busy, onClick = { editor = NovelEdit("编辑作品资料", listOf("书名" to work.optString("title"), "作者" to work.optString("author"), "简介" to work.optString("description"), "别名" to work.optString("aliases"))) { values ->
                        change(listOf("works", route.id, "metadata"), "PATCH", JSONObject().put("title", values.getValue("书名")).put("author", values.getValue("作者")).put("description", values.getValue("简介")).put("aliases", values.getValue("别名")))
                    } }) { Text("编辑资料") } }
                }
                if (route.kind == "author") data?.let { author -> item {
                    Text(author.optString("biography").ifBlank { "暂无作家简介" }); Text("${author.optJSONObject("stats")?.optInt("works") ?: 0} 部作品 · ${author.optJSONObject("stats")?.optInt("readable") ?: 0} 部可读 · ${author.optJSONObject("stats")?.optInt("completed") ?: 0} 部完结", style = MaterialTheme.typography.bodySmall)
                    Text(author.novelObjects("categories").joinToString(" · ") { "${it.optString("name")} ${it.optInt("count")}" }, style = MaterialTheme.typography.bodySmall)
                    NovelChoices("作品排序", query.sort, listOf("title" to "书名", "recent" to "最近", "popularity" to "热度")) { query = query.copy(sort = it) }
                    NovelChoices("连载状态", query.serialStatus, listOf("" to "全部", "ongoing" to "连载", "completed" to "完结", "hiatus" to "停更")) { query = query.copy(serialStatus = it) }
                    TextButton(enabled = editable && !busy, onClick = { editor = NovelEdit("编辑作家简介", listOf("简介" to author.optString("biography"))) { values -> change(listOf("authors", "profile"), "PATCH", JSONObject().put("name", route.name).put("biography", values.getValue("简介"))) } }) { Text("编辑简介") }
                } }
                if (route.kind == "lists") item { Row {
                    TextButton(enabled = editable && !busy, onClick = { editor = NovelEdit("新建书单", listOf("名称" to "", "简介" to "")) { values -> change(listOf("booklists"), "POST", JSONObject().put("title", values.getValue("名称")).put("description", values.getValue("简介"))) } }) { Text("新建书单") }
                    FilterChip(deletedLists, enabled = !busy, onClick = { deletedLists = !deletedLists }, label = { Text("已删除") })
                } }
                if (route.kind == "list") data?.let { list -> item {
                    Text(list.optString("description").ifBlank { "暂无书单简介" })
                    Row {
                        TextButton(enabled = editable && !busy, onClick = { editor = NovelEdit("编辑书单", listOf("名称" to list.optString("title"), "简介" to list.optString("description"))) { values -> change(listOf("booklists", route.id), "PATCH", JSONObject().put("title", values.getValue("名称")).put("description", values.getValue("简介"))) } }) { Text("编辑书单") }
                        TextButton(enabled = editable && !busy, onClick = { execute { repository.mutate(c, listOf("booklists", route.id), "DELETE"); route = NovelRoute("lists"); data = null; message = "书单已移至已删除，可恢复" } }) { Text("删除书单") }
                    }
                } }
                items(data?.novelObjects("items").orEmpty(), key = { item -> item.optString("id").ifBlank { item.optString("name").ifBlank { item.optJSONObject("work")?.optString("id").orEmpty() } } }) { item ->
                    when (route.kind) {
                        "works", "author" -> NovelWorkCard(item, enabled = !busy) { navigate(NovelRoute("work", item.getString("id"))) }
                        "authors" -> Card(onClick = { navigate(NovelRoute("author", name = item.getString("name"))) }, enabled = !busy) { Column(Modifier.fillMaxWidth().padding(18.dp)) { Text(item.getString("name"), style = MaterialTheme.typography.titleMedium); Text("${item.optInt("work_count")} 部作品"); if (item.optString("biography").isNotBlank()) Text(item.optString("biography"), maxLines = 3) } }
                        "lists" -> Card(enabled = !busy, onClick = { if (!deletedLists) navigate(NovelRoute("list", item.getString("id"))) }) { Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(item.optString("title"), style = MaterialTheme.typography.titleMedium); Text("${item.optInt("count")} 部 · ${item.optString("description")}"); Text(item.novelObjects("preview").joinToString(" · ") { it.optString("title") }, maxLines = 2)
                            if (deletedLists) TextButton(enabled = editable && !busy, onClick = { execute { change(listOf("booklists", item.getString("id"), "restore"), "POST") } }) { Text("恢复书单") }
                        } }
                        "list" -> Column {
                            val work = item.getJSONObject("work"); val path = listOf("booklists", route.id, "items", work.getString("id"))
                            NovelWorkCard(work, enabled = !busy) { navigate(NovelRoute("work", work.getString("id"))) }
                            if (item.optString("note").isNotBlank()) Text(item.optString("note"), Modifier.padding(12.dp))
                            FlowRow {
                                TextButton(enabled = editable && !busy, onClick = { editor = NovelEdit("推荐语", listOf("推荐语" to item.optString("note"))) { values -> change(path, "PATCH", JSONObject().put("note", values.getValue("推荐语"))) } }) { Text("编辑推荐语") }
                                TextButton(enabled = editable && !busy, onClick = { execute { change(path, "PATCH", JSONObject().put("move", "up")) } }) { Text("上移") }
                                TextButton(enabled = editable && !busy, onClick = { execute { change(path, "PATCH", JSONObject().put("move", "down")) } }) { Text("下移") }
                                TextButton(enabled = editable && !busy, onClick = { execute { change(path, "DELETE") } }) { Text("移出") }
                            }
                        }
                        "contents" -> TextButton(enabled = !busy && item.optBoolean("readable"), onClick = { open(route.id, route.version, item.getString("id")) }) { Text(item.optString("title")) }
                    }
                }
                if (route.kind in setOf("works", "authors", "author", "lists", "list", "contents")) {
                    item { Text("共 ${data?.optInt("total") ?: 0} 项", style = MaterialTheme.typography.bodySmall) }
                    if ((data?.novelObjects("items")?.size ?: 0) < (data?.optInt("total") ?: 0)) item { OutlinedButton(enabled = !busy, onClick = { execute { load(true) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
                }
            }
        }
    }
    if (connectionEditor) NovelConnectionEditor(editingConnection, busy, error, onDismiss = { connectionEditor = false }, onSave = { edited -> execute {
        repository.discover(edited)
        repository.store.replace(editingConnection, edited); connections = repository.store.load(); connectionEditor = false; message = "Novel 连接已验证"
    } })
    if (filter) NovelFilterSheet(query, navigation, onDismiss = { filter = false }, onApply = { query = it; filter = false })
    editor?.let { edit -> NovelEditSheet(edit, busy, error, onDismiss = { editor = null }, onSave = { values -> execute { edit.save(values); editor = null } }) }
    memberships?.let { lists -> ModalBottomSheet(onDismissRequest = { memberships = null }) { LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item { Text("选择书单", style = MaterialTheme.typography.titleLarge) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(lists, key = { it.getString("id") }) { list -> TextButton(enabled = !busy, onClick = { execute {
            repository.mutate(requireNotNull(c), listOf("booklists", list.getString("id"), "items", route.id), if (list.optBoolean("contains")) "DELETE" else "PUT")
            memberships = memberships?.map { current -> if (current.getString("id") == list.getString("id")) JSONObject(current.toString()).put("contains", !list.optBoolean("contains")) else current }
            load()
        } }) { Text((if (list.optBoolean("contains")) "✓ " else "+ ") + list.optString("title")) } }
        if (lists.size < membershipTotal) item { TextButton(enabled = !busy, onClick = { execute {
            val next = membershipOffset + PAGE
            val result = repository.get(requireNotNull(c), listOf("booklists"), listOf("work_id" to route.id, "offset" to next.toString(), "limit" to PAGE.toString()))
            memberships = lists + result.novelObjects("items"); membershipOffset = next
        } }) { Text("更多书单") } }
        if (lists.isEmpty()) item { Text("先到书单页新建书单。") }
    } } }
}

private const val PAGE = 48
private fun splitTags(text: String) = text.split(',', '，').map(String::trim).filter(String::isNotBlank).distinct()
private fun serialLabel(value: String) = when(value) { "completed" -> "完结"; "ongoing" -> "连载"; "hiatus" -> "停更"; else -> "状态未知" }
@Composable private fun NovelWorkCard(work: JSONObject, enabled: Boolean, onClick: () -> Unit) {
    Card(enabled = enabled, onClick = onClick) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(work.optString("title"), style = MaterialTheme.typography.titleMedium)
        Text(listOf(work.optString("author"), work.optString("category"), serialLabel(work.optString("serial_status"))).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (work.optString("description").isNotBlank()) Text(work.optString("description"), maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(work.novelStrings("genre_tags").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        val score = work.opt("popularity_score")
        if (score != null && score != JSONObject.NULL) Text("热度 $score · ${work.optJSONObject("popularity")?.optString("source").orEmpty()}", style = MaterialTheme.typography.labelMedium)
        if (work.optInt("update_count") > 0) Text("${work.optInt("update_count")} 章更新", color = MaterialTheme.colorScheme.primary)
    } }
}

@Composable private fun NovelConnectionEditor(existing: CatalogConnection?, busy: Boolean, operationError: String?, onDismiss: () -> Unit, onSave: (CatalogConnection) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "Shadow Novel") }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var allowHttp by remember { mutableStateOf(existing?.allowHttp ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) { LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Novel 连接", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }) }
        item { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("服务根地址") }, placeholder = { Text("https://example.com/novel/") }) }
        item { OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Basic 用户名（可选）") }) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Basic 密码") }, visualTransformation = PasswordVisualTransformation()) }
        item { OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bearer Token（可选，优先于 Basic）") }, visualTransformation = PasswordVisualTransformation()) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allowHttp, { allowHttp = it }); Text("允许 HTTP 明文连接（含凭据）") } }
        item { Text("修改地址或凭据会建立新账号作用域；旧下载和待同步进度保留。仅改名不影响同步。", style = MaterialTheme.typography.bodySmall) }
        (error ?: operationError)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Button(enabled = !busy, onClick = { try {
            require(name.isNotBlank()) { "请输入名称" }
            val value = CatalogConnection(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), CatalogKind.OPDS, url.trim(), username, password, token.trim(), allowHttp)
            value.base(); onSave(value)
        } catch (e: Exception) { error = e.message } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在验证…" else "验证并保存") } }
    } }
}

@Composable private fun NovelEditSheet(edit: NovelEdit, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val values = remember(edit) { mutableStateMapOf(*edit.fields.toTypedArray()) }
    ModalBottomSheet(onDismissRequest = onDismiss) { LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(edit.title, style = MaterialTheme.typography.titleLarge) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(edit.fields) { (key, _) -> OutlinedTextField(values[key].orEmpty(), { values[key] = it }, Modifier.fillMaxWidth(), label = { Text(key) }, minLines = if (key in setOf("简介", "推荐语")) 4 else 1) }
        item { Button(enabled = !busy, onClick = { onSave(values.toMap()) }, modifier = Modifier.fillMaxWidth()) { Text("保存") } }
    } }
}

@Composable private fun NovelFilterSheet(query: NovelQuery, navigation: JSONObject, onDismiss: () -> Unit, onApply: (NovelQuery) -> Unit) {
    var value by remember { mutableStateOf(query) }
    var tags by remember { mutableStateOf(query.personalTags.joinToString(",")) }
    var popularity by remember { mutableStateOf(query.minPopularity) }
    val facets = navigation.optJSONObject("facets") ?: JSONObject()
    fun toggle(list: List<String>, id: String) = if (id in list) list - id else list + id
    ModalBottomSheet(onDismissRequest = onDismiss) { LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("筛选与排序", style = MaterialTheme.typography.titleLarge) }
        item { NovelChoices("排序", value.sort, listOf("recent" to "最近", "title" to "书名", "popularity" to "热度")) { value = value.copy(sort = it) } }
        item { Text("分类（可多选）"); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { facets.novelObjects("categories").forEach { category -> val id = category.getString("id"); FilterChip(id in value.categories, onClick = { value = value.copy(categories = toggle(value.categories, id)) }, label = { Text("${category.optString("name")} ${category.optInt("count")}") }) } } }
        item { Text("题材：点击包含，再次点击移除；长列表支持下滑")
            Row { Switch(value.allGenres, { value = value.copy(allGenres = it) }); Text("匹配全部所选题材", Modifier.padding(12.dp)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { facets.novelObjects("genre_tags").forEach { genre -> val tag = genre.getString("value"); FilterChip(tag in value.genres, onClick = { value = value.copy(genres = toggle(value.genres, tag), excluded = value.excluded - tag) }, label = { Text(tag) }) } }
        }
        item { Text("排除题材"); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { facets.novelObjects("genre_tags").forEach { genre -> val tag = genre.getString("value"); FilterChip(tag in value.excluded, onClick = { value = value.copy(excluded = toggle(value.excluded, tag), genres = value.genres - tag) }, label = { Text(tag) }) } } }
        item { NovelChoices("连载", value.serialStatus, listOf("" to "全部", "ongoing" to "连载", "completed" to "完结", "hiatus" to "停更", "unknown" to "未知")) { value = value.copy(serialStatus = it) } }
        item { NovelChoices("内容", value.availability, listOf("" to "全部", "readable" to "可读", "local" to "本地版本", "online" to "在线版本", "metadata_only" to "仅资料")) { value = value.copy(availability = it) } }
        item { NovelChoices("个人状态", value.status, listOf("" to "全部", "want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停")) { value = value.copy(status = it) } }
        item { OutlinedTextField(value.group, { value = value.copy(group = it) }, Modifier.fillMaxWidth(), label = { Text("个人分组") }); OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("个人标签（逗号分隔）") }) }
        item { OutlinedTextField(popularity, { popularity = it.filter { char -> char.isDigit() || char == '.' } }, Modifier.fillMaxWidth(), label = { Text("最低热度 0–100（可留空）") }) }
        item { Row { TextButton(onClick = { value = NovelQuery(view = query.view, q = query.q); tags = ""; popularity = "" }) { Text("重置") }
            Button(enabled = popularity.isBlank() || popularity.toDoubleOrNull()?.let { it in 0.0..100.0 } == true, onClick = { onApply(value.copy(personalTags = splitTags(tags), minPopularity = popularity)) }) { Text("应用筛选") } } }
    } }
}
@Composable private fun NovelChoices(title: String, selected: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Text(title); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { (id, text) -> FilterChip(selected == id, { onSelect(id) }, label = { Text(text) }) } }
}
