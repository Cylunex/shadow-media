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
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<UnifiedMediaItem>()) }
    var detail by remember { mutableStateOf<UnifiedMediaItem?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(stored.exceptionOrNull()?.message) }
    var busy by remember { mutableStateOf(false) }
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
                    val page = p.search(ProviderSearchRequest(submittedQuery, pageToken = next))
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
                                    try { viewModel.playMdc(provider!!, item.key) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { busy = false }
                                }
                            }) { Text("播放 / 继续观看") }
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
                    OutlinedTextField(query, { query = it }, label = { Text("番号、标题或演员") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !busy, onClick = { load() }) { Text("搜索 / 刷新") }
                } }
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
