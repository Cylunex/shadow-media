@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package top.cylunex.shadowmedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.*

@Composable internal fun CatalogSourcesScreen(repository: NativeCatalogRepository, onBack: () -> Unit, onOpen: (LibraryAssetEntity) -> Unit, onQueue: (List<LibraryAssetEntity>) -> Unit) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<CatalogConnection>>(emptyList()) }
    var connection by remember { mutableStateOf<CatalogConnection?>(null) }
    var nodes by remember { mutableStateOf<List<String?>>(emptyList()) }
    var currentNode by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<CatalogPage?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var editing by remember { mutableStateOf<CatalogConnection?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<CatalogConnection?>(null) }
    var opening by remember { mutableStateOf<CatalogEntry?>(null) }
    fun runOperation(block: suspend CoroutineScope.() -> Unit) {
        val previous = job
        previous?.cancel()
        job = scope.launch { previous?.join(); block() }
    }
    fun refresh() { try { connections = repository.store.load() } catch (e: Exception) { error = e.message } }
    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun load(c: CatalogConnection, node: String? = null, next: String? = null) {
        connection = c; currentNode = node
        if (next == null) { page = null; query = "" }
        runOperation {
            loading = true; error = null
            try {
                val result = repository.browse(c, node, next)
                ensureActive()
                page = if (next != null) result.copy(entries = (page?.entries.orEmpty() + result.entries).distinctBy { it.locator to it.format }) else result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "来源加载失败" }
            finally { loading = false }
        }
    }
    fun back() {
        job?.cancel(); loading = false; error = null; query = ""
        when {
            nodes.isNotEmpty() -> { val previous = nodes.last(); nodes = nodes.dropLast(1); connection?.let { load(it, previous) } }
            connection != null -> { connection = null; page = null }
            else -> onBack()
        }
    }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text(connection?.name ?: "图书与有声书服务", maxLines = 1) }, navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
            if (connection == null) IconButton(onClick = { editing = null; editorVisible = true }) { Icon(Icons.Rounded.Add, "添加来源") }
        })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connection == null) {
                item { Text("连接你自己的 OPDS、Komga 或 Audiobookshelf。凭据使用 Android Keystore 加密保存在本机。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(connections, key = { it.id }) { c ->
                    Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (c.kind == CatalogKind.AUDIOBOOKSHELF) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { nodes = emptyList(); load(c) }, Modifier.weight(1f)) { Column { Text(c.name, style = MaterialTheme.typography.titleMedium); Text(c.kind.name, style = MaterialTheme.typography.bodySmall) } }
                        IconButton(onClick = { editing = c; editorVisible = true }) { Icon(Icons.Rounded.Edit, "编辑连接") }
                        IconButton(onClick = { remove = c }) { Icon(Icons.Rounded.DeleteOutline, "删除连接") }
                    } }
                }
                item { OutlinedButton(onClick = { editing = null; editorVisible = true }, Modifier.fillMaxWidth()) { Text("添加来源") } }
            } else {
                item { Text(page?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("筛选已加载目录") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true) }
                if (connection?.kind == CatalogKind.AUDIOBOOKSHELF && page?.entries?.any { it.format.isNotBlank() } == true) item {
                    Button(enabled = !loading, onClick = { val c = connection ?: return@Button; val entries = page?.entries.orEmpty().filter { it.format.isNotBlank() }; runOperation {
                        loading = true
                        try { val tracks = repository.addQueue(c, entries); ensureActive(); onQueue(tracks) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message }
                        finally { loading = false }
                    } }) { Text("加入本书音轨并顺序播放") }
                }
                items(page?.entries.orEmpty().filter { it.title.contains(query, true) || it.author.contains(query, true) }, key = { it.locator + ":" + it.format }) { entry ->
                    Card(enabled = !loading, onClick = {
                        if (entry.navigation != null) {
                            val c = connection ?: return@Card
                            nodes = nodes + currentNode
                            // Keep an explicit navigation stack, not a reconstructed URL.
                            load(c, entry.navigation)
                        } else opening = entry
                    }) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(if (entry.navigation != null) Icons.Rounded.FolderOpen else if (entry.format in setOf("m4b", "mp3", "m4a", "flac")) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) { Text(entry.title, style = MaterialTheme.typography.titleMedium); Text(entry.author.ifBlank { entry.format.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
                if (page?.entries?.isEmpty() == true && !loading) item { Text("目录为空，或没有支持的开放获取格式。") }
                page?.next?.let { next -> item { OutlinedButton(enabled = !loading, onClick = { connection?.let { load(it, currentNode, next) } }, modifier = Modifier.fillMaxWidth()) { Text("加载下一页") } } }
            }
        }
    }
    if (editorVisible) CatalogEditor(editing, onDismiss = { editorVisible = false }, onSave = { c ->
        try { repository.store.replace(editing, c); editorVisible = false; refresh() } catch (e: Exception) { error = e.message }
    })
    remove?.let { c -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("删除 ${c.name} 的连接？") }, text = { Text("移除本机凭据，保留已经下载的书籍和本地进度，不删除服务器内容。未同步进度将保留在本机。") }, confirmButton = { TextButton(onClick = { try { repository.store.remove(c.id); refresh() } catch (e: Exception) { error = e.message }; remove = null }) { Text("删除连接") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
    opening?.let { entry -> AlertDialog(onDismissRequest = { opening = null }, title = { Text(entry.title) }, text = { Text(if (entry.format == "komga") "按页读取漫画，不下载整本。阅读进度会排队同步到此 Komga 账号。" else if (connection?.kind == CatalogKind.AUDIOBOOKSHELF) "加入书架并流式播放，按轨道保存位置；播放进度会排队同步到此账号。" else "将资源加入书架。电子书需要下载本机副本后阅读，最多 1 GiB；不支持 DRM 借阅或购买流程。") }, confirmButton = { TextButton(onClick = {
        opening = null; val c = connection ?: return@TextButton
        runOperation { loading = true; try { val asset = repository.add(c, entry); ensureActive(); onOpen(asset) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false } }
    }) { Text("加入并打开") } }, dismissButton = { TextButton(onClick = { opening = null }) { Text("取消") } }) }
}

@Composable private fun CatalogEditor(existing: CatalogConnection?, onDismiss: () -> Unit, onSave: (CatalogConnection) -> Unit) {
    var kind by remember { mutableStateOf(existing?.kind ?: CatalogKind.OPDS) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var allowHttp by remember { mutableStateOf(existing?.allowHttp ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("${if (existing == null) "添加" else "编辑"}图书来源", style = MaterialTheme.typography.titleLarge) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CatalogKind.entries.forEach { value -> FilterChip(kind == value, { if (existing == null) kind = value }, { Text(if (value == CatalogKind.AUDIOBOOKSHELF) "ABS" else value.name) }) } } }
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }) }
            item { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(if (kind == CatalogKind.OPDS) "OPDS 目录地址" else "服务根地址") }, placeholder = { Text("https://example.com/") }) }
            if (kind != CatalogKind.AUDIOBOOKSHELF) {
                item { OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名（可选）") }) }
                item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, visualTransformation = PasswordVisualTransformation()) }
            }
            item { OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(if (kind == CatalogKind.AUDIOBOOKSHELF) "Audiobookshelf API Token" else "API Key / Bearer Token（可选）") }, visualTransformation = PasswordVisualTransformation()) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allowHttp, { allowHttp = it }); Text("允许 HTTP 明文传输（含凭据）") } }
            if (existing != null) item { Text("修改地址或凭据会建立新的账号作用域。旧书架与进度保留在本机，不会自动发给新账号；仅改名称不影响同步。", style = MaterialTheme.typography.bodySmall) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Button(onClick = {
                try {
                    require(name.isNotBlank()) { "请输入名称" }
                    require(kind != CatalogKind.AUDIOBOOKSHELF || token.isNotBlank()) { "请填写服务端生成的 API Token" }
                    val c = CatalogConnection(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), kind = kind, url = url.trim(), username = username, password = password, token = token.trim(), allowHttp = allowHttp)
                    c.base(); onSave(c)
                } catch (e: Exception) { error = e.message }
            }, Modifier.fillMaxWidth()) { Text("保存连接") } }
        }
    }
}
