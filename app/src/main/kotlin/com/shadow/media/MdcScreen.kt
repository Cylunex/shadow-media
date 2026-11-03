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
import com.shadow.media.provider.ProviderSearchRequest
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
    var actor by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf(emptyList<MdcPerson>()) }
    var year by rememberSaveable { mutableStateOf("") }
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
    var editor by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val connection = connections.singleOrNull { it.id == selected }
    val provider = remember(connection) { connection?.let(viewModel::mdcProvider) }
    val loader = remember(connection) {
        connection?.let { c ->
            val base = c.base()
            val client = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .addInterceptor(com.shadow.media.network.OfflineModeInterceptor())
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val builder = request.newBuilder().removeHeader("Authorization")
                    if (request.url.scheme == base.scheme && request.url.host == base.host &&
                        request.url.port == base.port &&
                        request.url.encodedPath.startsWith(base.newBuilder().addPathSegments("api/media/v1/works/").build().encodedPath)) {
                        builder.header("Authorization", "Bearer ${c.token}")
                    }
                    chain.proceed(builder.build())
                }.build()
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }.build()
        }
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
                else {
                    val filters = org.json.JSONObject().put("q", submittedQuery).put("sort", sort)
                    year.toIntOrNull()?.let { filters.put("year_from", it).put("year_to", it) }
                    if (studio.isNotBlank()) filters.put("organizations", org.json.JSONArray().put(studio))
                    if (tag.isNotBlank()) filters.put("tags", org.json.JSONArray().put(tag))
                    if (actor.isNotBlank()) filters.put("actors", org.json.JSONArray().put(actor))
                    if (next != null) filters.put("cursor", next)
                    val page = p.catalog(filters).first
                    if (next == null) facetData = p.facets(filters)
                    ensureActive()
                    rows = if (next == null) page.items else (rows + page.items).distinctBy { it.key }
                    cursor = page.nextPageToken
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(selected, workId) { if (provider != null) load() }
    fun back() {
        job?.cancel()
        when {
            workId != null -> { workId = null; detail = null }
            selected != null -> { selected = null; rows = emptyList(); query = "" }
            else -> viewModel.back()
        }
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text(detail?.title ?: connection?.name ?: "Shadow MDC") },
            navigationIcon = { TextButton(onClick = ::back) { Text("返回") } },
            actions = { if (connection == null) TextButton(onClick = { editor = true }) { Text("添加来源") } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connection == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("连接自己的 MDC 片库，在作品详情直接播放已关联的 Emby 影片。") }
                items(connections, key = { it.id }) { c -> Card {
                    TextButton(onClick = { selected = c.id }) { Text(c.name) }
                    TextButton(onClick = { store.remove(c.id); connections = store.load() }) { Text("移除连接") }
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
                        TextButton(onClick = { mode = "works"; actor = ""; load() }) { Text("全部作品") }
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
                    OutlinedTextField(query, { query = it }, label = { Text("番号、标题或演员") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(year, { year = it }, label = { Text("发行年份") }, modifier = Modifier.fillMaxWidth())
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
                    TextButton(enabled = !busy, onClick = { load() }) { Text("搜索 / 刷新") }
                } }
                if (mode == "actors") items(people.filter {
                    (it.name + it.aliases.joinToString()).contains(query, ignoreCase = true)
                }, key = { it.id }) { person ->
                    Card(onClick = { actor = person.id; mode = "works"; load() }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(person.name)
                            Text(person.aliases.joinToString(" / "))
                            Text("${person.workCount} 部作品")
                        }
                    }
                }
                if (mode == "works")
                items(rows, key = { it.key.stableId }) { item ->
                    Card(onClick = { detail = null; workId = item.key.itemId }) { Column(Modifier.padding(8.dp)) {
                        if (loader != null) AsyncImage(item.posterUrl, item.title, imageLoader = loader, modifier = Modifier.fillMaxWidth().height(180.dp))
                        Text(item.title, maxLines = 2)
                        Text(item.subtitle.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    } }
                }
                cursor?.let { next -> item(span = { GridItemSpan(maxLineSpan) }) {
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
    if (editor) AlertDialog(onDismissRequest = { if (!busy) editor = false }, title = { Text("添加 MDC") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text("名称") })
            OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, singleLine = true)
            OutlinedTextField(token, { token = it }, label = { Text("移动端访问令牌") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row { Checkbox(allowHttp, { allowHttp = it }); Text("允许 HTTP 明文连接") }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            job = scope.launch {
                busy = true; error = null
                try {
                    require(name.isNotBlank() && token.isNotBlank()) { "请填写名称和访问令牌" }
                    val c = CatalogConnection(name = name.trim(), kind = CatalogKind.OPDS, url = url.trim(), token = token.trim(), allowHttp = allowHttp)
                    viewModel.mdcProvider(c).checkConnection()
                    ensureActive(); store.save(c); connections = store.load(); editor = false; selected = c.id
                    name = ""; url = ""; token = ""
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { busy = false }
            }
        }) { Text("连接") } }, dismissButton = { TextButton(enabled = !busy, onClick = { editor = false }) { Text("取消") } })
}
