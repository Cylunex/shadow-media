@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.shadow.media.library.*
import com.shadow.media.reading.ReaderActivity

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
    var sourceSearch by remember(connection?.id, route) { mutableStateOf(false) }
    var editor by remember { mutableStateOf<NovelEdit?>(null) }
    var memberships by remember { mutableStateOf<List<JSONObject>?>(null) }
    var membershipOffset by remember { mutableIntStateOf(0) }
    var membershipTotal by remember { mutableIntStateOf(0) }
    var deletedLists by remember { mutableStateOf(false) }
    var disconnectedDownloads by remember { mutableStateOf<List<com.shadow.media.database.LibraryAssetEntity>>(emptyList()) }
    val pageStates = rememberSaveableStateHolder()
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            connections = repository.store.load()
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                connection = connections.singleOrNull { it.id == result.data?.getStringExtra("connectionId") }
                route = NovelRoute("home"); stack = emptyList(); data = null; error = null; connectionEditor = false
                NovelRepository.schedule(context)
            }
        } catch (e: Exception) { error = e.message }
    }
    fun signIn(name: String, url: String, previous: CatalogConnection?) {
        try {
            val value = CatalogConnection(name = name, kind = CatalogKind.OPDS, url = url)
            require(value.base().isHttps) { "统一认证需要 HTTPS 地址" }
            login.launch(Intent(context, NovelLoginActivity::class.java).putExtra("name", name).putExtra("url", url)
                .putExtra("connectionId", previous?.id))
        } catch (e: Exception) { error = e.message }
    }
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
        val requestedRoute = route
        val requestedQuery = query
        if (route.kind == "offline") { disconnectedDownloads = repository.downloaded(novelScope(c)); data = JSONObject(); return }
        val start = if (next) offset + PAGE else 0
        val pagination = listOf("offset" to start.toString(), "limit" to PAGE.toString())
        val result = when (route.kind) {
            "home" -> supervisorScope {
                suspend fun extra(path: List<String>, parameters: List<Pair<String, String>>): JSONObject = try { repository.get(c, path, parameters) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { JSONObject() }
                val home = async { repository.get(c, listOf("city", "home")) }
                val history = async { extra(listOf("works"), listOf("view" to "history", "limit" to "1")) }
                val lists = async { extra(listOf("booklists"), listOf("limit" to "3")) }
                home.await().put("_continue", history.await().novelObjects("items").firstOrNull())
                    .put("_booklists", lists.await().optJSONArray("items") ?: JSONArray())
            }
            "rankings" -> JSONObject()
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
        if (connection?.id != c.id || route != requestedRoute || query != requestedQuery) return
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
    CompositionLocalProvider(LocalNovelArtwork provides c?.let { NovelArtwork(repository, it) }) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text(if (c == null) "Shadow Novel" else when (route.kind) {
            "work" -> data?.optString("title") ?: "作品"; "author" -> route.name; "list" -> data?.optString("title") ?: "书单"; "contents" -> "版本目录"
            "home" -> "书城"; "lists" -> "我的书单"; "rankings" -> "排行榜"; "offline" -> "离线阅读"
            "works" -> if (route.id == "shelf") "我的书架" else if (route.id == "history") "阅读历史" else "发现好书"; else -> c.name
        }, maxLines = 1) }, navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
            if (c == null) IconButton(onClick = { editingConnection = null; connectionEditor = true }) { Icon(Icons.Rounded.Add, "添加 Novel") }
            else if (route.kind != "rankings") IconButton(enabled = !busy, onClick = { execute { load() } }) { Icon(Icons.Rounded.Refresh, "刷新") }
        })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (error != null && c?.oidcIssuer?.isNotBlank() == true) TextButton(enabled = !busy, onClick = { signIn(c.name, c.url, c) }) { Text("重新登录统一账号") }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary) }
        if (c == null) LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("连接自己的小说服务，在这里找书、管理书单与排行榜，接着上次的位置继续读。") }
            items(connections, key = { it.id }) { item -> Card {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { execute { repository.discover(item); connection = item; route = NovelRoute("home"); stack = emptyList(); NovelRepository.schedule(context) } }, modifier = Modifier.weight(1f)) { Text(item.name) }
                    TextButton(enabled = !busy, onClick = { connection = item; route = NovelRoute("offline"); stack = emptyList() }) { Text("离线") }
                    if (item.oidcIssuer.isNotBlank()) TextButton(enabled = !busy, onClick = {
                        if (item.oidcAuthState.isBlank()) signIn(item.name, item.url, item)
                        else execute { repository.oidc.logout(item); connections = repository.store.load(); message = "已退出统一账号；离线内容保留" }
                    }) { Text(if (item.oidcAuthState.isBlank()) "登录" else "退出") }
                    IconButton(onClick = { editingConnection = item; connectionEditor = true }) { Icon(Icons.Rounded.Edit, "编辑连接") }
                    IconButton(onClick = { try { repository.store.remove(item.id); connections = repository.store.load(); message = "连接已移除；下载与待同步进度保留在本机阅读书架" } catch (e: Exception) { error = e.message } }) { Icon(Icons.Rounded.LinkOff, "移除连接") }
                }
            } }
        } else {
            val roots = listOf("书城" to NovelRoute("home"), "书架" to NovelRoute("works", "shelf"), "书单" to NovelRoute("lists"), "排行" to NovelRoute("rankings"))
            val root = stack.firstOrNull() ?: route
            val tab = when { root.kind == "rankings" -> 3; root.kind in setOf("lists", "list") -> 2; root.kind == "offline" || root.kind == "works" && root.id in setOf("shelf", "history") -> 1; else -> 0 }
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 20.dp) {
                roots.forEachIndexed { i, (title, target) -> Tab(selected = i == tab, enabled = !busy,
                    onClick = { stack = emptyList(); search = ""; query = NovelQuery(); if (route == target) execute { load() } else { route = target; data = null } }, text = { Text(title) }) }
            }
            if (route.kind in setOf("home", "works", "authors", "lists", "offline")) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("分类找书", "搜索", "作家", "历史", "离线")) { label -> AssistChip(enabled = !busy, onClick = {
                    when (label) {
                        "分类找书" -> { query = NovelQuery(); navigate(NovelRoute("works", "city")); filter = true }
                        "搜索" -> { query = NovelQuery(); navigate(NovelRoute("works", "search")) }
                        "作家" -> navigate(NovelRoute("authors"))
                        "历史" -> { query = NovelQuery(); navigate(NovelRoute("works", "history")) }
                        else -> navigate(NovelRoute("offline"))
                    }
                }, label = { Text(label) }) }
            }
            if (data?.has("_cached_at") == true) Text("本机缓存 · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(data!!.getLong("_cached_at")))}", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            if (route.kind in setOf("works", "authors", "author", "lists", "list")) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, { search = it }, Modifier.weight(1f), placeholder = { Text("搜索服务端全部内容") }, singleLine = true)
                TextButton(enabled = !busy, onClick = { if (route.kind == "works" && query.q != search) query = query.copy(q = search) else execute { load() } }) { Text("搜索") }
                if (route.kind == "works") IconButton(onClick = { filter = true }) { Icon(Icons.Rounded.FilterList, "筛选与排序") }
            }
            pageStates.SaveableStateProvider("${c.id}:${route}") {
            if (route.kind == "rankings") NovelRankingsScreen(repository, c, onWork = { navigate(NovelRoute("work", it)) }, onSearch = { title ->
                query = NovelQuery(q = title); navigate(NovelRoute("works", "search")); search = title
            }, onLogin = { signIn(c.name, c.url, c) }) else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (route.kind == "home") {
                    item { Text(c.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    data?.optJSONObject("_continue")?.let { work -> item {
                        FilledTonalButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { open(work.getString("id")) }) { Text("继续阅读 · ${work.optString("title")}", maxLines = 1) }
                    } }
                    if (data?.novelObjects("_booklists")?.isNotEmpty() == true) {
                        item { Text("我的主题书单", style = MaterialTheme.typography.titleLarge) }
                        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data!!.novelObjects("_booklists"), key = { it.getString("id") }) { list ->
                                Box(Modifier.width(300.dp)) { NovelBooklistCard(list, !busy, { navigate(NovelRoute("list", list.getString("id"))) }) }
                            }
                        } }
                    }
                }
                if (route.kind == "offline") {
                    item { Text("已下载的章节窗口可离线阅读；不代表已下载全书。", style = MaterialTheme.typography.bodySmall) }
                    items(disconnectedDownloads, key = { it.id }) { asset -> Card(onClick = { context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("assetId", asset.id)) }) { Text(asset.title, Modifier.fillMaxWidth().padding(20.dp)) } }
                }
                if (route.kind == "home") data?.novelObjects("sections")?.forEach { section ->
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(enabled = !busy, onClick = {
                            query = novelSectionQuery(section)
                            navigate(NovelRoute("works", "city"))
                        }) { Text("全部 ${section.optInt("total")}") }
                    } }
                    item(key = "section:${section.optString("id")}") { NovelBookStrip(section.novelObjects("items"), !busy) { navigate(NovelRoute("work", it)) } }
                }
                if (route.kind == "home" && !busy && data != null && data!!.novelObjects("sections").isEmpty()) item {
                    Text("书城里还没有作品，收录到 Novel 的作品会出现在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (route.kind == "work") data?.let { work ->
                    item {
                        NovelWorkOverview(work, !busy, !LibraryResources.offlineOnly && work.optString("title").isNotBlank(),
                            onRead = { open(route.id) }, onAuthor = { navigate(NovelRoute("author", name = work.getString("author"))) },
                            onSearch = { sourceSearch = true })
                    }
                    item {
                        Text("我的阅读", style = MaterialTheme.typography.titleMedium)
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
                        "lists" -> NovelBooklistCard(item, !busy, { if (!deletedLists) navigate(NovelRoute("list", item.getString("id"))) }) {
                            if (deletedLists) TextButton(enabled = editable && !busy, onClick = { execute { change(listOf("booklists", item.getString("id"), "restore"), "POST") } }) { Text("恢复书单") }
                        }
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
                    if (!busy && data != null && data!!.novelObjects("items").isEmpty()) item {
                        Text(when (route.kind) { "lists" -> if (deletedLists) "没有已删除的书单" else "还没有书单，点新建书单，收藏同一主题下的好书。"; "list" -> "书单里还没有作品，可在作品详情里加入书单。"; else -> "这里暂时没有内容，试试其他分类或关键词。" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { Text("共 ${data?.optInt("total") ?: 0} 项", style = MaterialTheme.typography.bodySmall) }
                    if ((data?.novelObjects("items")?.size ?: 0) < (data?.optInt("total") ?: 0)) item { OutlinedButton(enabled = !busy, onClick = { execute { load(true) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
                }
            }
            }
        }
    }
    }
    if (connectionEditor) NovelConnectionEditor(editingConnection, busy, error, onDismiss = { connectionEditor = false }, onLogin = { name, url -> signIn(name, url, editingConnection) }, onSave = { edited -> execute {
        repository.discover(edited)
        repository.store.replace(editingConnection, edited); connections = repository.store.load(); connectionEditor = false; message = "Novel 连接已验证"
    } })
    if (filter) NovelFilterSheet(query, navigation, onDismiss = { filter = false }, onApply = { query = it; filter = false })
    if (sourceSearch && c != null && route.kind == "work") data?.let { work ->
        NovelSourceSearchSheet(repository, c, work, onDismiss = { sourceSearch = false }, onBound = {
            sourceSearch = false
            execute { load(); message = "已关联书源并获取目录，可选择版本阅读" }
        })
    }
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

@Composable private fun NovelConnectionEditor(existing: CatalogConnection?, busy: Boolean, operationError: String?, onDismiss: () -> Unit, onLogin: (String, String) -> Unit, onSave: (CatalogConnection) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "Shadow Novel") }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var allowHttp by remember { mutableStateOf(existing?.allowHttp ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unified by remember { mutableStateOf(existing == null || existing.oidcIssuer.isNotBlank()) }
    ModalBottomSheet(onDismissRequest = onDismiss) { LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Novel 连接", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }) }
        item { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("服务根地址") }, placeholder = { Text("https://example.com/novel/") }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = unified, onClick = { unified = true }, label = { Text("统一账号") })
            FilterChip(selected = !unified, onClick = { unified = false }, label = { Text("手动凭据") })
        } }
        if (unified) {
            item { Text("在系统浏览器中登录统一账号，登录后自动返回；同一账号续期保留下载和待同步进度。") }
            item { Button(enabled = !busy, onClick = { onLogin(name.trim().ifBlank { "Shadow Novel" }, url.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("登录统一账号") } }
        } else {
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
        }
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
