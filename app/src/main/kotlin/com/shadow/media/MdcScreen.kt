@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
    val connections = remember { mutableStateOf(stored.getOrDefault(emptyList())) }
    val selection = rememberSaveable { mutableStateOf<String?>(null) }
    val catalog = remember(selection.value) { MdcCatalogState(scope, stored.exceptionOrNull()?.message) }
    DisposableEffect(catalog) { onDispose(catalog::close) }
    catalog.Browse(viewModel, store, connections, selection)
}

@Composable
private fun MdcCatalogState.Browse(
    viewModel: MainViewModel, store: CatalogConnectionStore,
    connectionState: MutableState<List<CatalogConnection>>, selectedState: MutableState<String?>,
) {
    var connections by connectionState
    var selected by selectedState
    var workId by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by rememberSaveable(selected) { mutableStateOf(MdcFilters()) }
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("works") }
    var selectedPlaylist by rememberSaveable { mutableStateOf("") }
    var expandedFilters by rememberSaveable { mutableStateOf(false) }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var findSources by remember(workId) { mutableStateOf(false) }
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
    val connection = connections.singleOrNull { it.id == selected }
    val provider = remember(connection) { connection?.let(viewModel::mdcProvider) }
    val tvbox = remember(provider) { provider?.let(::MdcTvBoxProvider) }
    var showTvBox by rememberSaveable { mutableStateOf(false) }
    var supportsTvBox by remember(tvbox) { mutableStateOf(false) }
    LaunchedEffect(tvbox) {
        if (tvbox != null) {
            try { supportsTvBox = tvbox.supported() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { supportsTvBox = false }
        }
    }
    if (showTvBox && tvbox != null) {
        MdcTvBoxScreen(tvbox, viewModel, onBack = { showTvBox = false })
        return
    }
    val libraryScroll = rememberLazyGridState()
    val detailScroll = rememberLazyGridState()
    val loader = rememberMdcImageLoader(provider)
    var discoveryAttempt by remember { mutableIntStateOf(0) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(discoveryAttempt) {
        try {
            val discovery = viewModel.discoverMdcConnections(force = discoveryAttempt > 0)
            connections = discovery.connections
            discoveryError = discovery.failedAccounts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "未能连接：")
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { discoveryError = e.message ?: "服务发现失败" }
    }

    fun load(next: String? = null) {
        val previous = job
        previous?.cancel()
        val p = provider ?: return
        val requestedWork = workId
        val requestedMode = mode
        val requestedPlaylist = selectedPlaylist
        val requestedActor = selection.actor
        if (next == null) submittedQuery = query
        val filters = if (next != null) {
            org.json.JSONObject(loadedFilters?.toString() ?: return).put("cursor", next)
        } else selection.request(submittedQuery)

        if (next == null && requestedWork == null) {
            facetJob?.cancel()
            actorJob?.cancel()
            if (requestedMode == "works") {
                facetData = org.json.JSONObject()
                loadFacets(p, filters)
                loadActor(p, requestedActor)
            }
        }
        job = scope.launch {
            previous?.join()
            busy = true; error = null
            try {
                if (requestedWork != null) detail = p.detail(com.shadow.media.model.MediaKey(p.descriptor.id, requestedWork)).item
                else if (requestedMode == "playlists") {
                    playlists = p.playlists()
                    if (requestedPlaylist.isNotBlank()) playlistWorks = p.playlistWorks(requestedPlaylist)
                } else {
                    val (page, count) = p.catalog(filters)
                    ensureActive()
                    rows = if (next == null) page.items else (rows + page.items).distinctBy { it.key }
                    if (next == null) loadedFilters = filters
                    cursor = page.nextPageToken
                    total = count
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(provider, mode, query) {
        peoplePageJob?.cancel()
        val generation = ++peopleGeneration
        peopleCursor = null
        if (mode != "actors") return@LaunchedEffect
        val p = provider ?: return@LaunchedEffect
        people = emptyList()
        delay(180)
        loadPeoplePage(p, query.trim(), null, generation)
    }
    LaunchedEffect(provider, workId) { if (provider != null) load() }
    fun back() {
        job?.cancel()
        when {
            workId != null -> { workId = null; detail = null }
            selected != null -> {
                selected = null
                rows = emptyList()
                selection = MdcFilters()
                query = ""
                actorProfile = null
                mode = "works"
                selectedPlaylist = ""
                playlistWorks = emptyList()
                cursor = null
                loadedFilters = null
            }
            else -> viewModel.back()
        }
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text(detail?.title ?: connection?.takeUnless { it.isSharedMdc() }?.name ?: "Shadow MDC", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = ::back) { Text("返回") } },
            actions = { if (connection == null) TextButton(onClick = {
                editingConnection = null; name = ""; url = ""; token = ""; preferenceSpace = ""; includeInSearch = false; allowHttp = false; editor = true
            }) { Text("添加来源") } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), state = if (workId == null) libraryScroll else detailScroll, contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connection == null) {
                discoveryError?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { discoveryAttempt++ }) { Text("$message · 重试") }
                } }
                item(span = { GridItemSpan(maxLineSpan) }) { Text("我的服务共用统一账号。已登录 Novel 后，MDC 自动连接，无需再次认证。") }
                items(connections, key = { it.id }) { c -> Card {
                    TextButton(onClick = { selected = c.id }) { Text(c.name) }
                    if (c.isSharedMdc()) Text("已共用统一账号", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    else {
                    TextButton(onClick = {
                        editingConnection = c; name = c.name; url = c.url; token = c.token
                        preferenceSpace = c.username; allowHttp = c.allowHttp; includeInSearch = c.includeInSearch; editor = true
                    }) { Text("编辑连接") }
                    TextButton(onClick = {
                        try {
                            store.remove(c.id)
                            connections = connections.filter { it.isSharedMdc() } + store.load()
                            viewModel.refreshProviderConnections()
                        } catch (e: IllegalStateException) { error = e.message }
                    }) { Text("移除连接") }
                    }
                } }
            } else if (workId != null) {
                detail?.let { item ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item.posterUrl?.let { if (loader != null) MdcArtwork(it, item.title, loader, Modifier.height(240.dp).fillMaxWidth()) }
                            Text(item.title, style = MaterialTheme.typography.headlineSmall)
                            Text(item.subtitle.orEmpty())
                            MdcRelatedFilters(provider!!, item) { filters ->
                                selection = filters
                                query = ""
                                mode = "works"
                                workId = null
                                detail = null
                            }
                            Button(enabled = !busy, onClick = {
                                job?.cancel()
                                job = scope.launch {
                                    busy = true; error = null
                                    try {
                                        val plan = provider!!.plan(item.key)
                                        if (plan.optJSONObject("emby") == null &&
                                            (plan.optJSONArray("sources")?.length() ?: 0) > 1) {
                                            pendingPlan = plan
                                        } else if (plan.optJSONObject("emby") == null && (plan.optJSONArray("sources")?.length() ?: 0) == 0) {
                                            findSources = true
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
                                            findSources = true
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
                            TextButton(onClick = { findSources = true }) { Text("在影视仓跨站找片") }
                            Text(item.overview.orEmpty())
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    Row {
                        if (supportsTvBox) TextButton(onClick = { showTvBox = true }) { Text("TVBox") }
                        TextButton(onClick = { mode = "works"; selection = MdcFilters(); query = ""; load() }) { Text("全部作品") }
                        TextButton(enabled = !busy, onClick = { mode = "playlists"; load() }) { Text("我的片单") }
                        TextButton(enabled = !busy, onClick = { mode = "actors"; query = "" }) { Text("演员库") }
                    }
                    if (mode == "works" && selection.actor.isNotBlank()) actorProfile?.let { profile ->
                        Text(profile.optString("name"), style = MaterialTheme.typography.headlineSmall)
                        profile.optJSONArray("aliases")?.let { aliases ->
                            Text((0 until aliases.length()).joinToString(" / ") { aliases.getString(it) })
                        }
                        profile.optString("biography").takeUnless { it.isBlank() || it == "null" }?.let { Text(it) }
                        val years = profile.optJSONArray("years")
                        if (years != null && years.length() > 0) Text("本库作品年份：${years.getInt(years.length() - 1)}–${years.getInt(0)}")
                        listOf("collaborators" to "合作演员", "organizations" to "合作机构", "series" to "参演系列").forEach { (key, caption) ->
                            val values = profile.optJSONArray(key)
                            if (values != null && values.length() > 0) {
                                Text(caption, style = MaterialTheme.typography.titleSmall)
                                androidx.compose.foundation.lazy.LazyRow {
                                    items(values.length()) { index ->
                                        val value = values.getJSONObject(index)
                                        SuggestionChip(onClick = {
                                            when (key) {
                                                "collaborators" -> selection = selection.copy(actor = value.getString("id"))
                                                "series" -> selection = selection.copy(series = value.getString("id"))
                                                else -> selection = selection.copy(studio = value.getString("id"))
                                            }
                                            query = ""; load()
                                        }, label = { Text("${value.getString("name")} · ${value.getInt("count")}") })
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text(if (mode == "actors") "搜索演员姓名或别名" else "番号、标题或演员") }, modifier = Modifier.fillMaxWidth())
                    if (mode == "works") {
                    Text("$total 部作品", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { expandedFilters = !expandedFilters }) { Text(if (expandedFilters) "收起筛选" else "分类与筛选") }
                    if (expandedFilters) {
                    OutlinedTextField(selection.year, { selection = selection.copy(year = it) }, label = { Text("发行年份") }, modifier = Modifier.fillMaxWidth())
                    val decades = facetData.optJSONArray("decades")
                    if (decades != null) androidx.compose.foundation.lazy.LazyRow {
                        items(decades.length()) { index ->
                            val value = decades.getJSONObject(index)
                            if (value.getString("id") != "unknown") FilterChip(selection.decade == value.getString("id"), {
                                selection = selection.copy(decade = if (selection.decade == value.getString("id")) "" else value.getString("id"), year = "", unknownYear = false); load()
                            }, { Text("${value.getString("name")} 年代 · ${value.getInt("count")}") })
                        }
                    }
                    val organizations = facetData.optJSONArray("organizations")
                    if (organizations != null && organizations.length() > 0) Text("片商及制作机构")
                    if (organizations != null) androidx.compose.foundation.lazy.LazyRow {
                        items(organizations.length()) { index ->
                            val value = organizations.getJSONObject(index)
                            FilterChip(selection.studio == value.getString("id"), {
                                selection = selection.copy(studio = if (selection.studio == value.getString("id")) "" else value.getString("id"))
                                load()
                            }, { Text(value.getString("name")) })
                        }
                    }
                    listOf("labels" to "厂牌", "platforms" to "发布平台", "series" to "作品系列").forEach { (key, caption) ->
                        val choices = facetData.optJSONArray(key)
                        if (choices != null && choices.length() > 0) {
                            Text(caption, style = MaterialTheme.typography.titleSmall)
                            androidx.compose.foundation.lazy.LazyRow {
                                items(choices.length()) { index ->
                                    val choice = choices.getJSONObject(index)
                                    val id = choice.getString("id")
                                    val current = when (key) { "labels" -> selection.label; "platforms" -> selection.platform; else -> selection.series }
                                    FilterChip(current == id, {
                                        val next = if (current == id) "" else id
                                        when (key) { "labels" -> selection = selection.copy(label = next); "platforms" -> selection = selection.copy(platform = next); else -> selection = selection.copy(series = next) }
                                        load()
                                    }, { Text("${choice.getString("name")} · ${choice.getInt("count")}") })
                                }
                            }
                        }
                    }
                    if (facetError != null) TextButton(onClick = {
                        val p = provider
                        val current = loadedFilters
                        if (p != null && current != null) loadFacets(p, current)
                    }) { Text("重试筛选统计") }
                    if (actorError != null && selection.actor.isNotBlank()) TextButton(onClick = {
                        provider?.let { loadActor(it, selection.actor) }
                    }) { Text("重试演员资料") }
                    MdcTagFilters(facetData.optJSONArray("tags"), selection.tag) { selection = selection.copy(tag = it); load() }
                    Row {
                        Checkbox(selection.unknownYear, { selection = selection.copy(unknownYear = it, year = "", decade = ""); load() })
                        Text("发行年待补")
                    }
                    Row {
                        listOf("added" to "最近入库", "newest" to "最新发行", "title" to "片名").forEach { (key, text) ->
                            FilterChip(selection.sort == key, { selection = selection.copy(sort = key); load() }, { Text(text) })
                        }
                    }
                    listOf("genres" to "题材", "forms" to "形式", "regions" to "制作地区").forEach { (key, label) ->
                        val values = facetData.optJSONArray(key)
                        if (values != null && values.length() > 0) {
                            Text(label)
                            androidx.compose.foundation.lazy.LazyRow {
                                items(values.length()) { index ->
                                    val value = values.getJSONObject(index)
                                    val current = when (key) { "genres" -> selection.genre; "forms" -> selection.form; else -> selection.region }
                                    FilterChip(current == value.getString("id"), {
                                        val next = if (current == value.getString("id")) "" else value.getString("id")
                                        when (key) { "genres" -> selection = selection.copy(genre = next); "forms" -> selection = selection.copy(form = next); else -> selection = selection.copy(region = next) }
                                        load()
                                    }, { Text(value.getString("name")) })
                                }
                            }
                        }
                    }
                    Row {
                        listOf("all" to "全部", "emby" to "Emby", "files" to "有文件", "metadata" to "仅资料").forEach { (key, label) ->
                            FilterChip(selection.status == key, { selection = selection.copy(status = key); load() }, { Text(label) })
                        }
                    }
                    TextButton(onClick = {
                        selection = MdcFilters()
                        query = ""
                        load()
                    }) { Text("清除条件") }
                    }
                    }
                    TextButton(enabled = !busy, onClick = { load() }) { Text("搜索 / 刷新") }
                } }
                if (mode == "playlists") {
                    if (!busy && playlists.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("暂无片单") }
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
                if (mode == "actors") items(people, key = { it.id }) { person ->
                    Card(onClick = { selection = selection.copy(actor = person.id); mode = "works"; query = ""; actorProfile = null; load() }) {
                        Column(Modifier.padding(16.dp)) {
                            if (loader != null) MdcArtwork(person.imageUrl, person.name, loader,
                                Modifier.fillMaxWidth().height(150.dp), "暂无头像")
                            Text(person.name)
                            Text(person.aliases.joinToString(" / "))
                            Text("${person.workCount} 部作品")
                        }
                    }
                }
                if (mode == "actors" && (peopleCursor != null || peopleError != null || peopleBusy)) item(span = { GridItemSpan(maxLineSpan) }) {
                    if (peopleBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else TextButton(onClick = {
                        val p = provider ?: return@TextButton
                        val generation = peopleGeneration
                        val next = peopleCursor.takeIf { peopleError == null }
                        peoplePageJob = scope.launch { loadPeoplePage(p, query.trim(), next, generation) }
                    }) { Text(if (peopleError == null) "加载更多演员" else "重试演员目录") }
                }
                if (mode == "works") {
                items(rows, key = { it.key.stableId }) { item ->
                    Card(onClick = { detail = null; workId = item.key.itemId }) { Column(Modifier.padding(8.dp)) {
                        if (loader != null) MdcArtwork(item.posterUrl, item.title, loader, Modifier.fillMaxWidth().height(180.dp))
                        Text(item.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(item.subtitle.orEmpty(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    } }
                }
                }
                cursor?.takeIf { mode == "works" }?.let { next -> item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(enabled = !busy, onClick = { load(next) }) { Text("加载下一页") }
                } }
            }
        }
    }
    if (findSources) detail?.let { work ->
        MdcCrossSiteSearch(work, viewModel, onClose = { findSources = false })
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

@Composable
internal fun MdcArtwork(url: String?, description: String, loader: MdcImageLoader, modifier: Modifier,
    unavailable: String = "暂无封面") {
    var failed by remember(url, loader) { mutableStateOf(url.isNullOrBlank()) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val context = LocalContext.current
        val request = remember(url, loader) {
            val key = "${loader.connectionId}:$url"
            coil3.request.ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).build()
        }
        AsyncImage(request, description, imageLoader = loader.images, modifier = Modifier.fillMaxSize(),
            onSuccess = { failed = false }, onError = { failed = true })
        if (failed) Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}


internal data class MdcImageLoader(val connectionId: String, val images: ImageLoader)

@Composable
internal fun rememberMdcImageLoader(provider: MdcMediaProvider?): MdcImageLoader? {
    val context = LocalContext.current.applicationContext
    val loader = remember(provider) {
        provider?.let {
            MdcImageLoader(it.connection.id, ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { it.imageClient })) }.build())
        }
    }
    DisposableEffect(loader) { onDispose { loader?.images?.shutdown() } }
    return loader
}
