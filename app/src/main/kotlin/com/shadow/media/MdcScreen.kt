@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.shadow.media.library.*
import com.shadow.media.model.UnifiedMediaItem
import kotlinx.coroutines.*

@Composable
internal fun MdcScreen(viewModel: MainViewModel) {
    val context = LocalContext.current.applicationContext
    val store = remember { CatalogConnectionStore(context, "mdc_connections", "shadow_mdc_v1") }
    val scope = rememberCoroutineScope()
    val stored = remember { runCatching { store.load() } }
    var connections by remember { mutableStateOf(stored.getOrDefault(emptyList())) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var workId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("works") }
    var selectedPlaylist by rememberSaveable { mutableStateOf("") }
    var playlists by remember { mutableStateOf(emptyList<MdcPlaylist>()) }
    var playlistWorks by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var genre by rememberSaveable { mutableStateOf("") }
    var form by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var unknownYear by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("all") }
    var actor by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf(emptyList<MdcPerson>()) }
    var year by rememberSaveable { mutableStateOf("") }
    var decade by rememberSaveable { mutableStateOf("") }
    var actorProfile by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var expandedFilters by rememberSaveable { mutableStateOf(false) }
    var total by remember { mutableStateOf(0) }
    var studio by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("added") }
    var facetData by remember { mutableStateOf(org.json.JSONObject()) }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<UnifiedMediaItem>()) }
    var detail by remember { mutableStateOf<UnifiedMediaItem?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(stored.exceptionOrNull()?.message) }
    var busy by remember { mutableStateOf(false) }
    var pendingPlan by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var picking by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var matchQuery by remember { mutableStateOf("") }
    var editingConnection by remember { mutableStateOf<CatalogConnection?>(null) }
    var editor by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var preferenceSpace by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var includeInSearch by remember { mutableStateOf(false) }
    var allowHttp by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val connection = connections.singleOrNull { it.id == selected }
    val provider = remember(connection) { connection?.let(viewModel::mdcProvider) }
    val libraryScroll = rememberLazyGridState()
    val detailScroll = rememberLazyGridState()
    val loader = remember(provider) {
        provider?.let { p -> ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { p.imageClient })) }.build() }
    }
    LaunchedEffect(Unit) {
        busy = true
        try { connections = viewModel.discoverMdcConnections() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { busy = false }
    }
    DisposableEffect(loader) { onDispose { loader?.shutdown() } }
    fun load(next: String? = null) {
        val previous = job
        previous?.cancel()
        if (next == null) submittedQuery = query
        job = scope.launch {
            previous?.join()
            busy = true; error = null
            try {
                val p = provider ?: return@launch
                if (workId != null) detail = p.detail(com.shadow.media.model.MediaKey(p.descriptor.id, workId!!)).item
                else if (mode == "playlists") {
                    playlists = p.playlists()
                    if (selectedPlaylist.isNotBlank()) playlistWorks = p.playlistWorks(selectedPlaylist)
                } else {
                    val filters = org.json.JSONObject().put("q", submittedQuery).put("sort", sort)
                    filters.put("unknown_year", unknownYear).put("status", status)
                    if (genre.isNotBlank()) filters.put("genres", org.json.JSONArray().put(genre))
                    if (form.isNotBlank()) filters.put("forms", org.json.JSONArray().put(form))
                    if (region.isNotBlank()) filters.put("regions", org.json.JSONArray().put(region))
                    decade.toIntOrNull()?.let { filters.put("year_from", it).put("year_to", it + 9) }
                    year.toIntOrNull()?.let { filters.put("year_from", it).put("year_to", it) }
                    if (studio.isNotBlank()) filters.put("organizations", org.json.JSONArray().put(studio))
                    if (tag.isNotBlank()) filters.put("tags", org.json.JSONArray().put(tag))
                    if (actor.isNotBlank()) filters.put("actors", org.json.JSONArray().put(actor))
                    if (next != null) filters.put("cursor", next)
                    val (page, count) = p.catalog(filters)
                    if (next == null) facetData = p.facets(filters)
                    if (actor.isNotBlank() && next == null) actorProfile = p.person(actor)
                    else if (actor.isBlank()) actorProfile = null
                    ensureActive()
                    rows = if (next == null) page.items else (rows + page.items).distinctBy { it.key }
                    cursor = page.nextPageToken
                    total = count
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(provider, workId) { if (provider != null) load() }
    fun back() {
        job?.cancel()
        when {
            workId != null -> { workId = null; detail = null }
            selected != null -> {
                selected = null; rows = emptyList(); query = ""; actor = ""; actorProfile = null
                year = ""; decade = ""; studio = ""; tag = ""; genre = ""; form = ""; region = ""
                unknownYear = false; status = "all"; mode = "works"; selectedPlaylist = ""; playlistWorks = emptyList()
            }
            else -> viewModel.back()
        }
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text(detail?.title ?: connection?.name ?: "Shadow MDC", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = ::back) { Text("返回") } },
            actions = { if (connection == null) TextButton(onClick = {
                editingConnection = null; name = ""; url = ""; token = ""; preferenceSpace = ""; includeInSearch = false; allowHttp = false; editor = true
            }) { Text("添加来源") } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), state = if (workId == null) libraryScroll else detailScroll, contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connection == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("我的服务共用统一账号。已登录 Novel 后，MDC 自动连接，无需再次认证。") }
                items(connections, key = { it.id }) { c -> Card {
                    TextButton(onClick = { selected = c.id }) { Text(c.name) }
                    if (c.isSharedMdc()) Text("已共用统一账号", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    else {
                    TextButton(onClick = {
                        editingConnection = c; name = c.name; url = c.url; token = c.token
                        preferenceSpace = c.username; allowHttp = c.allowHttp; includeInSearch = c.includeInSearch; editor = true
                    }) { Text("编辑连接") }
                    TextButton(onClick = { store.remove(c.id); connections = connections.filter { it.isSharedMdc() } + store.load(); viewModel.refreshProviderConnections() }) { Text("移除连接") }
                    }
                } }
            } else if (workId != null) {
                detail?.let { item ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item.posterUrl?.let { if (loader != null) AsyncImage(it, item.title, imageLoader = loader, modifier = Modifier.height(240.dp).fillMaxWidth()) }
                            Text(item.title, style = MaterialTheme.typography.headlineSmall)
                            Text(item.subtitle.orEmpty())
                            Button(enabled = !busy, onClick = {
                                job?.cancel()
                                job = scope.launch {
                                    busy = true; error = null
                                    try {
                                        val plan = provider!!.plan(item.key)
                                        if (plan.optJSONObject("emby") == null &&
                                            (plan.optJSONArray("sources")?.length() ?: 0) > 1) {
                                            pendingPlan = plan
                                        } else viewModel.playMdc(provider!!, item.key, preparedPlan = plan)
                                    }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { busy = false }
                                }
                            }) { Text("播放 / 继续观看") }
                            TextButton(enabled = !busy, onClick = {
                                job = scope.launch {
                                    busy = true; error = null
                                    try {
                                        val plan = provider!!.plan(item.key, online = true)
                                        if ((plan.optJSONArray("sources")?.length() ?: 0) == 0) {
                                            error = "暂无已确认的在线线路"
                                        } else pendingPlan = plan
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { busy = false }
                                }
                            }) { Text("在线版本与线路") }
                            TextButton(enabled = !busy, onClick = {
                                matchQuery = item.subtitle?.substringBefore(" · ").orEmpty().ifBlank { item.title }
                                picking = true
                            }) { Text("选择或更换 Emby 影片") }
                            Text(item.overview.orEmpty())
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    Row {
                        TextButton(onClick = { mode = "works"; actor = ""; query = ""; load() }) { Text("全部作品") }
                        TextButton(enabled = !busy, onClick = { mode = "playlists"; load() }) { Text("我的片单") }
                        TextButton(enabled = !busy, onClick = {
                            mode = "actors"
                            job?.cancel()
                            job = scope.launch {
                                busy = true; error = null
                                try { people = provider!!.people() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                                finally { busy = false }
                            }
                        }) { Text("演员库") }
                    }
                    if (mode == "works" && actor.isNotBlank()) actorProfile?.let { profile ->
                        Text(profile.optString("name"), style = MaterialTheme.typography.headlineSmall)
                        profile.optJSONArray("aliases")?.let { aliases ->
                            Text((0 until aliases.length()).joinToString(" / ") { aliases.getString(it) })
                        }
                        profile.optString("biography").takeUnless { it.isBlank() || it == "null" }?.let { Text(it) }
                        val years = profile.optJSONArray("years")
                        if (years != null && years.length() > 0) Text("本库作品年份：${years.getInt(years.length() - 1)}–${years.getInt(0)}")
                        listOf("collaborators" to "合作演员", "organizations" to "合作机构").forEach { (key, label) ->
                            val values = profile.optJSONArray(key)
                            if (values != null && values.length() > 0) {
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                androidx.compose.foundation.lazy.LazyRow {
                                    items(values.length()) { index ->
                                        val value = values.getJSONObject(index)
                                        SuggestionChip(onClick = {
                                            if (key == "collaborators") actor = value.getString("id")
                                            else studio = value.getString("id")
                                            query = ""; load()
                                        }, label = { Text("${value.getString("name")} · ${value.getInt("count")}") })
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("番号、标题或演员") }, modifier = Modifier.fillMaxWidth())
                    if (mode == "works") {
                    Text("$total 部作品", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { expandedFilters = !expandedFilters }) { Text(if (expandedFilters) "收起筛选" else "分类与筛选") }
                    if (expandedFilters) {
                    OutlinedTextField(year, { year = it }, label = { Text("发行年份") }, modifier = Modifier.fillMaxWidth())
                    val decades = facetData.optJSONArray("decades")
                    if (decades != null) androidx.compose.foundation.lazy.LazyRow {
                        items(decades.length()) { index ->
                            val value = decades.getJSONObject(index)
                            if (value.getString("id") != "unknown") FilterChip(decade == value.getString("id"), {
                                decade = if (decade == value.getString("id")) "" else value.getString("id")
                                year = ""; unknownYear = false; load()
                            }, { Text("${value.getString("name")} 年代 · ${value.getInt("count")}") })
                        }
                    }
                    val organizations = facetData.optJSONArray("organizations")
                    if (organizations != null) androidx.compose.foundation.lazy.LazyRow {
                        items(organizations.length()) { index ->
                            val value = organizations.getJSONObject(index)
                            FilterChip(studio == value.getString("id"), {
                                studio = if (studio == value.getString("id")) "" else value.getString("id")
                                load()
                            }, { Text(value.getString("name")) })
                        }
                    }
                    val tags = facetData.optJSONArray("tags")
                    if (tags != null) androidx.compose.foundation.lazy.LazyRow {
                        items(tags.length()) { index ->
                            val value = tags.getJSONObject(index)
                            FilterChip(tag == value.getString("id"), {
                                tag = if (tag == value.getString("id")) "" else value.getString("id")
                                load()
                            }, { Text(value.getString("name")) })
                        }
                    }
                    Row {
                        Checkbox(unknownYear, { unknownYear = it; year = ""; decade = ""; load() })
                        Text("发行年待补")
                    }
                    Row {
                        listOf("added" to "最近入库", "newest" to "最新发行", "title" to "片名").forEach { (key, text) ->
                            FilterChip(sort == key, { sort = key; load() }, { Text(text) })
                        }
                    }
                    listOf("genres" to "题材", "forms" to "形式", "regions" to "制作地区").forEach { (key, label) ->
                        val values = facetData.optJSONArray(key)
                        if (values != null && values.length() > 0) {
                            Text(label)
                            androidx.compose.foundation.lazy.LazyRow {
                                items(values.length()) { index ->
                                    val value = values.getJSONObject(index)
                                    val current = when (key) { "genres" -> genre; "forms" -> form; else -> region }
                                    FilterChip(current == value.getString("id"), {
                                        val next = if (current == value.getString("id")) "" else value.getString("id")
                                        when (key) { "genres" -> genre = next; "forms" -> form = next; else -> region = next }
                                        load()
                                    }, { Text(value.getString("name")) })
                                }
                            }
                        }
                    }
                    Row {
                        listOf("all" to "全部", "emby" to "Emby", "files" to "有文件", "metadata" to "仅资料").forEach { (key, label) ->
                            FilterChip(status == key, { status = key; load() }, { Text(label) })
                        }
                    }
                    TextButton(onClick = {
                        year = ""; decade = ""; unknownYear = false; genre = ""; form = ""; region = ""
                        studio = ""; tag = ""; actor = ""; query = ""; status = "all"; load()
                    }) { Text("清除条件") }
                    }
                    }
                    TextButton(enabled = !busy, onClick = { load() }) { Text("搜索 / 刷新") }
                } }
                if (mode == "playlists") {
                    items(playlists, key = { "playlist:" + it.id }) { playlist ->
                        Card(onClick = { selectedPlaylist = playlist.id; load() }) {
                            Column(Modifier.padding(16.dp)) { Text(playlist.name); Text("${playlist.workCount} 部") }
                        }
                    }
                    items(playlistWorks, key = { "playlist-work:" + it.first }) { work ->
                        Card(onClick = { workId = work.first; detail = null }) {
                            Text(work.second, Modifier.padding(16.dp))
                        }
                    }
                }
                if (mode == "actors") items(people.filter {
                    (it.name + it.aliases.joinToString()).contains(query, ignoreCase = true)
                }, key = { it.id }) { person ->
                    Card(onClick = { actor = person.id; mode = "works"; query = ""; actorProfile = null; load() }) {
                        Column(Modifier.padding(16.dp)) {
                            if (loader != null) AsyncImage(person.imageUrl, person.name, imageLoader = loader,
                                modifier = Modifier.fillMaxWidth().height(150.dp))
                            Text(person.name)
                            Text(person.aliases.joinToString(" / "))
                            Text("${person.workCount} 部作品")
                        }
                    }
                }
                if (mode == "works") {
                items(rows, key = { it.key.stableId }) { item ->
                    Card(onClick = { detail = null; workId = item.key.itemId }) { Column(Modifier.padding(8.dp)) {
                        if (loader != null) AsyncImage(item.posterUrl, item.title, imageLoader = loader, modifier = Modifier.fillMaxWidth().height(180.dp))
                        Text(item.title, maxLines = 2)
                        Text(item.subtitle.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    } }
                }
                }
                cursor?.takeIf { mode == "works" }?.let { next -> item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(enabled = !busy, onClick = { load(next) }) { Text("加载下一页") }
                } }
            }
        }
    }
    pendingPlan?.let { plan ->
        AlertDialog(onDismissRequest = { pendingPlan = null }, title = { Text("版本与线路") },
            text = { Column {
                val sources = plan.getJSONArray("sources")
                repeat(sources.length()) { index ->
                    val source = sources.getJSONObject(index)
                    TextButton(enabled = !busy, onClick = {
                        job = scope.launch {
                            busy = true; error = null
                            try {
                                viewModel.playMdc(provider!!, detail!!.key, plan, source)
                                pendingPlan = null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    }) { Text(source.optString("label", "线路 ${index + 1}")) }
                }
            } }, confirmButton = { TextButton(onClick = { pendingPlan = null }) { Text("关闭") } })
    }
    if (picking) AlertDialog(onDismissRequest = { if (!busy) picking = false }, title = { Text("关联 Emby 影片") },
        text = { Column {
            OutlinedTextField(matchQuery, { matchQuery = it }, label = { Text("搜索 Emby 条目") })
            TextButton(enabled = !busy, onClick = {
                job = scope.launch {
                    busy = true; error = null
                    try { candidates = provider!!.candidates(detail!!.key, matchQuery) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { busy = false }
                }
            }) { Text("查找") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(candidates.size) { index ->
                    val candidate = candidates[index]
                    TextButton(enabled = !busy, onClick = {
                        job = scope.launch {
                            busy = true; error = null
                            try { provider!!.bind(detail!!.key, candidate.first); picking = false; candidates = emptyList() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    }) { Text(candidate.second + " · " + candidate.first) }
                }
            }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { picking = false }) { Text("关闭") } })
    if (editor) AlertDialog(onDismissRequest = { if (!busy) editor = false }, title = { Text(if (editingConnection == null) "添加 MDC" else "编辑 MDC") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text("名称") })
            OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, singleLine = true)
            OutlinedTextField(preferenceSpace, { preferenceSpace = it }, label = { Text("片单标识（可从 Web 片单页复制）") }, singleLine = true)
            OutlinedTextField(token, { token = it }, label = { Text("移动端访问令牌") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row { Checkbox(includeInSearch, { includeInSearch = it }); Text("加入聚合搜索") }
            Row { Checkbox(allowHttp, { allowHttp = it }); Text("允许 HTTP 明文连接") }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            job = scope.launch {
                busy = true; error = null
                try {
                    require(name.isNotBlank() && token.isNotBlank()) { "请填写名称和访问令牌" }
                    require(preferenceSpace.isBlank() || preferenceSpace.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                        "片单标识请使用 Web 页面提供的标识"
                    }
                    val c = CatalogConnection(id = editingConnection?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.trim(), kind = CatalogKind.OPDS, url = url.trim(), username = preferenceSpace.trim(), token = token.trim(), allowHttp = allowHttp, includeInSearch = includeInSearch)
                    viewModel.mdcProvider(c).checkConnection()
                    ensureActive()
                    val saved = store.replace(editingConnection, c)
                    connections = connections.filter { it.isSharedMdc() } + store.load(); editor = false; selected = saved.id
                    viewModel.refreshProviderConnections()
                    name = ""; url = ""; token = ""; preferenceSpace = ""
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { busy = false }
            }
        }) { Text("连接") } }, dismissButton = { TextButton(enabled = !busy, onClick = { editor = false }) { Text("取消") } })
}
