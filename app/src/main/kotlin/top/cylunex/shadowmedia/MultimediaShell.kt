@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia

import android.content.Intent
import android.content.res.Configuration
import top.cylunex.shadowmedia.library.LibraryResources
import top.cylunex.shadowmedia.library.ResourceScheduler
import top.cylunex.shadowmedia.library.ResourcePriority
import top.cylunex.shadowmedia.audio.embeddedLyrics
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import top.cylunex.shadowmedia.audio.*
import top.cylunex.shadowmedia.model.isAudio
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.library.LibraryRepository
import top.cylunex.shadowmedia.reading.ComicActivity
import top.cylunex.shadowmedia.reading.ReaderActivity

private val destinations = listOf("影视" to Icons.Rounded.Movie, "直播" to Icons.Rounded.LiveTv, "阅读" to Icons.AutoMirrored.Rounded.MenuBook,
    "音频" to Icons.Rounded.Headphones, "来源" to Icons.Rounded.Storage)

@Composable fun ShadowMediaRoot(viewModel: MainViewModel, container: AppContainer) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingSync by container.library.dao.pendingCount().collectAsStateWithLifecycle(0)
    val progressError by top.cylunex.shadowmedia.library.ProgressWriter.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var musicTab by rememberSaveable { mutableStateOf(false) }
    var audioExpanded by rememberSaveable { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val audioState = rememberAudioUi(controller, visible = false)
    val queueError by AudioQueueStatus.persistenceError.collectAsStateWithLifecycle()
    val queueMessage by AudioQueueStatus.recoveryMessage.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    fun startImport(block: suspend CoroutineScope.() -> Unit) {
        val previous = importJob
        previous?.cancel()
        importJob = scope.launch {
            previous?.join() // Old cleanup must finish before the next task owns the dialog/files.
            block()
        }
    }
    var continueScreen by rememberSaveable { mutableStateOf(false) }
    var workAsset by remember { mutableStateOf<LibraryAssetEntity?>(null) }
    var offlineScreen by rememberSaveable { mutableStateOf(false) }
    var catalogScreen by rememberSaveable { mutableStateOf(false) }
    val playerScreen = state.screen in setOf(Screen.PLAYER, Screen.EXTERNAL_PLAYER)
    val television = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    DisposableEffect(Unit) {
        val future = AudiobookController.connect(context.applicationContext)
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it } }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }
    LaunchedEffect(controller, playerScreen) {
        if (playerScreen) { controller?.pause(); audioExpanded = false }
    }
    LaunchedEffect(queueError, queueMessage) { (queueError ?: queueMessage)?.let { message = it } }
    fun open(item: LibraryAssetEntity) {
        if (item.kind in setOf("MOVIE", "EPISODE")) { viewModel.continueLibraryVideo(item); return }
        if (item.kind in setOf("AUDIOBOOK", "MUSIC", "PODCAST")) {
            val control = controller
            if (control == null) { message = "音频服务正在连接，请稍后重试"; return }
            scope.launch {
                try {
                    AudiobookController.play(control, listOf(item.id), item.id, top.cylunex.shadowmedia.model.AudioMode.valueOf(item.kind))
                    audioExpanded = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "无法打开音频，请检查文件授权或重新导入" }
            }
        } else {
            if (item.format == "komga") {
                context.startActivity(Intent(context, ComicActivity::class.java).putExtra("assetId", item.id))
                return
            }
            if (item.localUri.isBlank()) {
                startImport {
                    try {
                        importing = "准备保存到本机，完成后离线阅读"
                        val candidate = top.cylunex.shadowmedia.library.LibraryResources.resolve(item)
                        val saved = container.library.fetchRemote(item, candidate) { read, total -> importing = "已下载 ${read / 1024 / 1024} MiB" + (total?.let { " / ${it / 1024 / 1024} MiB" } ?: "") }
                        ensureActive()
                        val target = if (saved.kind == "COMIC" || saved.format == "pdf") ComicActivity::class.java else ReaderActivity::class.java
                        context.startActivity(Intent(context, target).putExtra("assetId", saved.id))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "资源获取失败，请检查来源连接、权限和文件格式" }
                    finally { importing = null }
                }
                return
            }
            val target = if (item.kind == "COMIC" || item.format == "pdf") ComicActivity::class.java else ReaderActivity::class.java
            context.startActivity(Intent(context, target).putExtra("assetId", item.id))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            startImport {
                var success = 0; val failed = mutableListOf<String>()
                try {
                    uris.take(2000).forEachIndexed { index, uri ->
                        importing = "正在导入 ${index + 1} / ${uris.size}"
                        try {
                            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            container.library.importDocument(uri); success++
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed += e.message ?: "文件不受支持" }
                    }
                    message = "已导入 $success 个文件" + if (failed.isEmpty()) "" else "，${failed.size} 个失败：${failed.first()}"
                } finally { importing = null }
            }
        }
    }
    val directoryImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            startImport {
                importing = "正在保存漫画目录的离线副本"
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    container.library.importImageDirectory(uri)
                    message = "漫画目录已导入，原图片未修改"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "目录导入失败" }
                finally { importing = null }
            }
        }
    }
    fun select(index: Int) { tab = index; audioExpanded = false; catalogScreen = false; offlineScreen = false; continueScreen = false; viewModel.showHome() }
    if (playerScreen) { LegacyMediaRoot(viewModel, container); return }
    BackHandler(enabled = audioExpanded) { audioExpanded = false }
    Scaffold(bottomBar = {
        if (!television && !audioExpanded) Column {
            if (audioState.id != null) AudioMiniBar(controller, onOpen = { audioExpanded = true }, onToggle = { controller?.let { toggleAudio(it) } })
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                destinations.forEachIndexed { index, (title, icon) -> NavigationBarItem(selected = tab == index, onClick = { select(index) }, icon = { Icon(icon, title) }, label = { Text(title) }) }
            }
        }
    }) { padding ->
        Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            if (television && !audioExpanded) NavigationRail(Modifier.fillMaxHeight().statusBarsPadding(), containerColor = MaterialTheme.colorScheme.background) {
                Text("SHADOW", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.labelLarge)
                destinations.forEachIndexed { index, (title, icon) -> NavigationRailItem(selected = tab == index, onClick = { select(index) }, icon = { Icon(icon, title) }, label = { Text(title) }) }
                if (audioState.id != null) NavigationRailItem(selected = false, onClick = { audioExpanded = true }, icon = { Icon(Icons.Rounded.GraphicEq, "正在播放") })
            }
            Column(Modifier.weight(1f)) {
                if (tab == 3 && !audioExpanded && !catalogScreen && state.screen == Screen.HOME) {
                    Row(Modifier.statusBarsPadding().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(!musicTab, onClick = { musicTab = false }, label = { Text("听书") })
                        FilterChip(musicTab, onClick = { musicTab = true }, label = { Text("音乐") })
                    }
                }
                when {
                    audioExpanded -> AudioNowPlaying(controller, container.library, onBack = { audioExpanded = false })
                    continueScreen -> ContinueScreen(container, onBack = { continueScreen = false }, onOpen = ::open, onLegacy = viewModel::continueLegacy)
                    offlineScreen -> OfflineScreen(container, onBack = { offlineScreen = false }, onOpen = { asset ->
                        if (asset.kind in setOf("MOVIE", "EPISODE")) viewModel.playOfflineAsset(asset) else open(asset)
                    })
                    tab == 3 && musicTab && state.screen == Screen.HOME && !catalogScreen -> MusicScreen(container, controller, onPlaying = { audioExpanded = true })
                    catalogScreen -> CatalogSourcesScreen(container.catalogs, onConnectionsChanged = viewModel::refreshProviderConnections, onBack = { catalogScreen = false }, onOpen = ::open, onQueue = { tracks ->
                        val control = controller
                        if (control != null && tracks.isNotEmpty()) scope.launch {
                            try {
                                top.cylunex.shadowmedia.library.ProgressWriter.flush()
                                val selected = tracks.firstOrNull { track -> container.library.dao.progress(track.id)?.completed != true } ?: tracks.first()
                                AudiobookController.play(control, tracks.map { it.id }, selected.id)
                                audioExpanded = true
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "听书队列加载失败，请检查来源或重新添加" }
                        } else if (control == null) message = "音频服务正在连接，请稍后重试"
                    })
                    (tab == 2 || tab == 3) && state.screen == Screen.HOME -> PublicationShelf(container.library, audio = tab == 3, onImport = { importer.launch(arrayOf("*/*")) }, onOpen = ::open,
                        onDirectory = { directoryImporter.launch(null) }, onAssociate = { workAsset = it },
                        onOffline = { item -> scope.launch {
                            try { container.offline.enqueue(item); message = "已加入离线任务" }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message ?: "无法加入离线任务" }
                        } },
                        onFavorite = { item -> scope.launch { container.library.dao.favorite(item.id, !item.favorite) } },
                        onRemove = { item -> scope.launch {
                            importJob?.cancelAndJoin()
                            controller?.let { control ->
                                for (i in control.mediaItemCount - 1 downTo 0) if (control.getMediaItemAt(i).mediaId == item.id) control.removeMediaItem(i)
                            }
                            container.library.remove(item.id)
                        } })
                    tab == 4 && state.screen == Screen.HOME -> SourcesHub(state, viewModel, pendingSync, onImport = { importer.launch(arrayOf("*/*")) }, onCatalogs = { catalogScreen = true }, onOffline = { offlineScreen = true }, onContinue = { continueScreen = true })
                    tab == 1 && state.screen == Screen.HOME -> LiveLanding(state, container, viewModel)
                    else -> LegacyMediaRoot(viewModel, container)
                }
            }
        }
    }
    importing?.let { label -> AlertDialog(onDismissRequest = {}, title = { Text(label) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { importJob?.cancel() }) { Text("取消") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
    if (progressError != null) Text(progressError!!, Modifier.statusBarsPadding().padding(16.dp), color = MaterialTheme.colorScheme.error)
    workAsset?.let { asset -> WorkAssociationSheet(container, asset, onDismiss = { workAsset = null }, onOpen = { workAsset = null; open(it) }) }
    state.pendingPublication?.let { item ->
        AlertDialog(onDismissRequest = viewModel::clearPendingPublication, title = { Text(item.title) }, text = { Text(if (item.key.providerId == "library") "打开书架中的内容并恢复本机进度。" else if (top.cylunex.shadowmedia.model.contentKind(item.type).isAudio()) "加入听书库并在线播放，不需要下载整本。" else "将下载一份本机副本后打开阅读，最大 1 GiB。会使用网络和本机空间，可随时取消；不修改来源文件。") },
            confirmButton = { TextButton(onClick = {
                viewModel.clearPendingPublication()
                scope.launch {
                  try {
                    if (item.key.providerId == "library") {
                        container.library.dao.asset(item.key.itemId)?.let(::open)
                        return@launch
                    }
                    val format = item.key.itemId.substringBefore('?').substringAfterLast('.', "").lowercase()
                    val extension = if (format in setOf("epub", "txt", "pdf", "cbz", "zip", "m4b", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")) format else if (top.cylunex.shadowmedia.model.contentKind(item.type).isAudio()) "m4b" else "epub"
                    val asset = if (top.cylunex.shadowmedia.model.contentKind(item.type) == top.cylunex.shadowmedia.model.ContentKind.MUSIC) container.music.importRemote(item) else container.library.addRemote(item, extension)
                    open(asset)
                  } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { message = "无法加入书库，请检查来源连接或重新导入" }
                }
            }) { Text("加入并打开") } }, dismissButton = { TextButton(onClick = viewModel::clearPendingPublication) { Text("取消") } })
    }
}

@Composable private fun PublicationShelf(
    library: LibraryRepository, audio: Boolean,
    onImport: () -> Unit, onDirectory: () -> Unit, onAssociate: (LibraryAssetEntity) -> Unit, onOffline: (LibraryAssetEntity) -> Unit, onOpen: (LibraryAssetEntity) -> Unit, onFavorite: (LibraryAssetEntity) -> Unit, onRemove: (LibraryAssetEntity) -> Unit,
) {
    var filter by rememberSaveable(audio) { mutableStateOf("全部") }
    var query by rememberSaveable(audio) { mutableStateOf("") }
    var sort by rememberSaveable(audio) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LibraryAssetEntity?>(null) }
    var removing by remember { mutableStateOf<LibraryAssetEntity?>(null) }
    val kinds = remember(audio, filter) { if (audio) listOf("AUDIOBOOK") else when (filter) { "小说" -> listOf("BOOK"); "漫画" -> listOf("COMIC"); else -> listOf("BOOK", "COMIC") } }
    val flow = remember(library, kinds, query, filter, sort) { Pager(PagingConfig(60, enablePlaceholders = false)) {
        library.dao.shelf(kinds, "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", filter == "收藏", filter == "未完成", sort)
    }.flow }
    val books = flow.collectAsLazyPagingItems()
    val recentRow by remember(kinds) { library.dao.continuing(kinds) }.collectAsStateWithLifecycle(null)
    val recent = recentRow?.asset
    LazyVerticalGrid(columns = GridCells.Adaptive(if (audio) 144.dp else 116.dp), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(if (audio) "听书" else "阅读", style = MaterialTheme.typography.headlineLarge); Text(if (audio) "让故事陪你走远一点" else "把时间留给下一章", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = onImport) { Icon(Icons.Rounded.Add, "导入文件") }
                    if (!audio) IconButton(onClick = onDirectory) { Icon(Icons.Rounded.FolderOpen, "导入漫画图片目录") }
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索标题、作者") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (audio) listOf("全部", "未完成", "收藏") else listOf("全部", "小说", "漫画", "收藏")).forEach { label -> FilterChip(filter == label, { filter = label }, { Text(label) }) }
                }
            }
        }
        if (recent != null && query.isBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            Card(onClick = { onOpen(recent) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PublicationCover(recent, Modifier.width(72.dp).height(96.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (audio) "继续收听" else "接着读", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(recent.title, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                        Text("${((recentRow?.progression ?: 0.0) * 100).toInt()}% · 进度已保存在本机", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(if (audio) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (audio) "我的有声书" else "我的书架", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { sort = !sort }) { Text(if (sort) "名称排序" else "最近加入") }
        } }
        if (books.itemCount == 0) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(if (audio) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (query.isNotBlank()) "没有匹配的作品" else if (audio) "从第一本有声书开始" else "这里放得下你的整个书架", style = MaterialTheme.typography.titleMedium)
                Text(if (audio) "导入 M4B、MP3、M4A、FLAC 等音频" else "导入 EPUB、TXT、PDF、CBZ 或 ZIP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onImport) { Text("导入文件") }
            }
        }
        items(books.itemCount, key = books.itemKey { it.asset.id }, contentType = { "publication" }) { index ->
            val row = books[index] ?: return@items
            val item = row.asset
            Column {
            Box(Modifier.clickable { onOpen(item) }) {
                PublicationCover(item, Modifier.fillMaxWidth().aspectRatio(if (audio) 1f else .69f))
                if (item.favorite) Icon(Icons.Rounded.Favorite, "已收藏", Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f).clickable { onOpen(item) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { selected = item }) { Icon(Icons.Rounded.MoreHoriz, "作品选项") }
            }
            Text(item.author.ifBlank { item.format.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            row.progression?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant) }
        } }
    }
    selected?.let { item -> ModalBottomSheet(onDismissRequest = { selected = null }) {
        Column(Modifier.padding(24.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { selected = null; onOpen(item) }) { Text(if (audio) "开始收听" else "打开阅读") }
            if (item.providerId != "local" && item.localUri.isBlank()) TextButton(onClick = { selected = null; onOffline(item) }) { Text("保存离线副本（最多 1 GiB）") }
            if (item.localUri.isNotBlank()) Text("此内容可从本机打开", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { selected = null; onAssociate(item) }) { Text("作品与其他版本") }
            TextButton(onClick = { selected = null; onFavorite(item) }) { Text(if (item.favorite) "取消收藏" else "收藏") }
            TextButton(onClick = { selected = null; removing = item }) { Text("从书架移除", color = MaterialTheme.colorScheme.error) }
        }
    } }
    removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除《${item.title}》？") }, text = { Text("会移除本机副本、书签和进度，不会删除原始文件或服务器内容。") },
        confirmButton = { TextButton(onClick = { removing = null; onRemove(item) }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

@Composable private fun PublicationCover(item: LibraryAssetEntity, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF3B4657), Color(0xFF1E252F))))) {
        if (item.coverPath.isNotBlank()) AsyncImage(File(item.coverPath), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(if (item.kind in setOf("AUDIOBOOK", "MUSIC", "PODCAST")) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.MenuBook, null, tint = Color(0xFFB5C7E0))
            Text(item.title, color = Color.White, maxLines = 4, fontWeight = FontWeight.SemiBold)
            Text(item.format.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB5C7E0))
        }
    }
}

@Composable private fun SourcesHub(state: MainUiState, viewModel: MainViewModel, pendingSync: Int, onImport: () -> Unit, onCatalogs: () -> Unit, onOffline: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val appearance = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(appearance.getString("theme", "深色")) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { FilledTonalButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("继续播放与阅读") } }
        item { OutlinedButton(onClick = onOffline, modifier = Modifier.fillMaxWidth()) { Text("离线任务与存储") } }
        item { Text("来源", style = MaterialTheme.typography.headlineLarge); Text("你的内容，按自己的方式连接", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SourceTile("Emby 媒体服务", "${state.savedSessions.size} 个已保存账号", Icons.Rounded.Dns, viewModel::showServers) }
        item { SourceTile("网络存储", "OpenList / WebDAV / SMB · ${state.networkStorages.size} 个连接", Icons.Rounded.FolderOpen, viewModel::showNetworkStorages) }
        item { SourceTile("影视与直播订阅", "${state.externalSources.size} 个订阅 · 导入、更新与诊断", Icons.Rounded.LiveTv, viewModel::showSources) }
        item { SourceTile("本地书籍与音频", "系统文件选择器 · 不需要全盘存储权限", Icons.Rounded.FileOpen, onImport) }
        item { SourceTile("图书与音乐服务", "OPDS / Komga / ABS / Jellyfin / OpenSubsonic", Icons.AutoMirrored.Rounded.MenuBook, onCatalogs) }
        item { SourceTile("聚合搜索", "搜索已连接的内容服务", Icons.Rounded.Search, viewModel::showDiscover) }
        item { SourceTile("设置与诊断", "功能开关、播放记录与问题排查", Icons.Rounded.Tune, viewModel::showSettings) }
        item { Text(if (pendingSync > 0) "待发送进度：$pendingSync 条 · 应用打开时自动重试，已移除账号的记录保留在本机" else "当前没有待发送的进度记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("界面外观", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("深色", "浅色", "跟随系统").forEach { value ->
                FilterChip(theme == value, { theme = value; appearance.edit().putString("theme", value).apply() }, { Text(value) })
            } }
        }
    }
}

@Composable private fun SourceTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LiveLanding(state: MainUiState, container: AppContainer, viewModel: MainViewModel) {
    val sources = remember(state.externalSources) { container.externalSourceStore.loadAll().filter { it.liveCount > 0 || it.kind == top.cylunex.shadowmedia.model.ExternalSourceKind.LIVE_PLAYLIST } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("直播", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge); IconButton(onClick = viewModel::showSources) { Icon(Icons.Rounded.Add, "导入直播订阅") } }; Text("频道、节目单与回看", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (sources.isEmpty()) item { SourceTile("添加直播订阅", "支持 M3U、TXT 与 XMLTV 节目单", Icons.Rounded.AddLink, viewModel::showSources) }
        items(sources, key = { it.id }) { source -> SourceTile(source.name, "${source.liveCount} 个直播条目 · 查看频道与节目单", Icons.Rounded.LiveTv) { viewModel.openExternalSource(source) } }
    }
}

private fun toggleAudio(player: Player) {
    if (player.playerError == null && player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
    else {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
}

private fun playerScreenForAudio(screen: Screen) = screen in setOf(Screen.PLAYER, Screen.EXTERNAL_PLAYER)

@Composable private fun rememberAudioUi(controller: MediaController?, visible: Boolean): AudioUi {
    var state by remember(controller) { mutableStateOf(AudioUi()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun snapshot(player: Player): AudioUi = AudioUi(player.currentMediaItem?.mediaId,
        player.mediaMetadata.title?.toString().orEmpty(), player.playerError == null && player.playWhenReady && player.playbackState != Player.STATE_ENDED,
        player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it > 0 } ?: 0,
        player.playbackParameters.speed, player.playbackState == Player.STATE_BUFFERING,
        player.playerError?.let { "播放失败，请检查来源连接或文件权限。" },
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }, player.currentMediaItemIndex, player.isPlaying, player.audioChapters())
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { state = snapshot(player) }
        }
        controller?.let { it.addListener(listener); state = snapshot(it) }
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller, visible, state.advancing, lifecycle) {
        val player = controller ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state = snapshot(player)
            if (player.isPlaying) while (isActive) {
                delay(500)
                // Metadata and the queue are event-driven; ticks only update the visible position.
                state = state.copy(position = player.currentPosition.coerceAtLeast(0))
            }
        }
    }
    return state
}

private data class AudioUi(val id: String? = null, val title: String = "", val playing: Boolean = false, val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val buffering: Boolean = false, val error: String? = null, val queue: List<androidx.media3.common.MediaItem> = emptyList(), val currentIndex: Int = 0, val advancing: Boolean = false, val chapters: List<AudioChapter> = emptyList())
private fun audioTime(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) }

@Composable private fun AudioMiniBar(controller: MediaController?, onOpen: () -> Unit, onToggle: () -> Unit) {
    val state = rememberAudioUi(controller, visible = true)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column { Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${audioTime(state.position)} · ${state.speed}×", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = onToggle) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "继续收听") }
        }; if (state.duration > 0) LinearProgressIndicator(progress = { (state.position.toFloat() / state.duration).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp)) }
    }
}

@Composable private fun AudioNowPlaying(controller: MediaController?, library: LibraryRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberAudioUi(controller, visible = true)
    var item by remember(state.id) { mutableStateOf<LibraryAssetEntity?>(null) }
    LaunchedEffect(state.id) { item = state.id?.let { library.dao.asset(it) } }
    val currentChapter = state.chapters.chapterAt(state.position)
    val sleep by AudioSleep.remainingMs.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<String?>(null) }
    var drag by remember(state.currentIndex, state.queue.getOrNull(state.currentIndex)) { mutableStateOf<Float?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }; Text("正在收听", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton(onClick = { panel = "队列" }) { Icon(Icons.Rounded.QueueMusic, "播放队列") } } }
        item { if (item != null) PublicationCover(requireNotNull(item), Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)) else Icon(Icons.Rounded.Headphones, null, Modifier.size(140.dp)) }
        item { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(item?.title ?: state.title.ifBlank { "选择一本有声书" }, style = MaterialTheme.typography.headlineSmall, maxLines = 3); Text(item?.author.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (state.chapters.isNotEmpty()) item {
            TextButton(onClick = { panel = "章节" }) {
                Icon(Icons.Rounded.FormatListBulleted, null)
                Text("${currentChapter?.title ?: "查看章节"} · ${state.chapters.size} 章", maxLines = 2)
            }
        }
        item { Column(Modifier.fillMaxWidth()) {
            Slider(value = drag ?: if (state.duration > 0) state.position.toFloat() / state.duration else 0f, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { controller?.seekTo((it * state.duration).toLong()) }; drag = null }, enabled = state.duration > 0 && controller?.isCurrentMediaItemSeekable == true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(audioTime(state.position)); Text(audioTime(state.duration)) }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = controller?.hasPreviousMediaItem() == true) { Icon(Icons.Rounded.SkipPrevious, "上一轨") }
            IconButton(onClick = { controller?.seekTo((state.position - 10000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay10, "后退 10 秒") }
            FilledIconButton(onClick = { controller?.let { toggleAudio(it) } }, Modifier.size(76.dp)) { if (state.buffering) CircularProgressIndicator(Modifier.size(30.dp)) else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "播放", Modifier.size(42.dp)) }
            IconButton(onClick = { controller?.seekTo((state.position + 30000).coerceAtMost(state.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }) { Icon(Icons.Rounded.Forward30, "前进 30 秒") }
            IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = controller?.hasNextMediaItem() == true) { Icon(Icons.Rounded.SkipNext, "下一轨") }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (item?.kind != "MUSIC") TextButton(onClick = { panel = "倍速" }) { Text("${state.speed}× 倍速") }
            TextButton(onClick = { panel = "睡眠" }) { Text(if (sleep == -2L) "本章结束" else if (sleep == -1L) "本轨结束" else if (sleep > 0) audioTime(sleep) else "睡眠定时") }
            TextButton(onClick = { scope.launch { state.id?.let { library.bookmark(it, JSONObject().put("positionMs", state.position).toString(), audioTime(state.position)) }; panel = "书签" } }) { Text("标记") }
        } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            val method by AudioDiagnostics.playbackMethod.collectAsStateWithLifecycle()
            val format by AudioDiagnostics.decoderInput.collectAsStateWithLifecycle()
            if (format.isNotBlank()) Text("${method.ifBlank { "本机" }} · 解码输入 $format", style = MaterialTheme.typography.bodySmall)
        }
        item { Row {
            if (item?.kind == "MUSIC") {
                TextButton(onClick = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }) { Text(if (controller?.shuffleModeEnabled == true) "随机播放 · 开" else "随机播放 · 关") }
                TextButton(onClick = { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }) { Text(when (controller?.repeatMode) { Player.REPEAT_MODE_ONE -> "单曲循环"; Player.REPEAT_MODE_ALL -> "列表循环"; else -> "顺序播放" }) }
                TextButton(onClick = { panel = "歌词" }) { Text("歌词") }
            } else TextButton(onClick = { panel = "书签" }) { Text("查看所有书签") }
        } }
        if (currentChapter != null) item { Row {
            TextButton(onClick = { controller?.let { AudiobookController.chapter(it, false) } }) { Text("上一章") }
            Text("本章剩余 ${audioTime((currentChapter.endMs ?: state.duration) - state.position)}", Modifier.padding(12.dp))
            TextButton(enabled = state.chapters.any { it.startMs > currentChapter.startMs } || controller?.hasNextMediaItem() == true, onClick = { controller?.let { AudiobookController.chapter(it, true) } }) { Text("下一章") }
        } }
    }
    if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null }) {
        Column(Modifier.padding(24.dp)) {
            Text(panel!!, style = MaterialTheme.typography.titleLarge)
            when (panel) {
                "歌词" -> {
                    val context = LocalContext.current
                    var lyrics by remember(state.id) { mutableStateOf("") }
                    var lyricSource by remember(state.id) { mutableStateOf("") }
                    LaunchedEffect(state.id, controller?.currentTracks) {
                        val id = state.id ?: return@LaunchedEffect
                        val dao = ShadowMediaDatabase.create(context).musicDao()
                        val embedded = controller?.embeddedLyrics()
                        lyrics = embedded ?: dao.track(id)?.lyrics.orEmpty()
                        lyricSource = if (embedded != null) "内嵌歌词" else if (lyrics.isNotBlank()) "本机歌词" else ""
                        if (lyrics.isBlank() && !LibraryResources.offlineOnly) {
                            try {
                                val asset = library.dao.asset(id) ?: return@LaunchedEffect
                                val remote = ResourceScheduler.process.run(ResourcePriority.VISIBLE) { LibraryResources.lyricsResolver?.invoke(asset) }
                                if (!remote.isNullOrBlank() && remote.length <= 512 * 1024) {
                                    dao.fillLyrics(id, remote); lyrics = remote; lyricSource = "服务端歌词"
                                }
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Lyrics must not interrupt playback. */ }
                        }
                    }
                    if (lyricSource.isNotBlank()) Text(lyricSource, style = MaterialTheme.typography.labelSmall)
                    AudioLyricsPanel(lyrics, state.position) { controller?.seekTo(it) }
                }
                "章节" -> LazyColumn(Modifier.heightIn(max = 480.dp)) { items(state.chapters, key = { it.startMs }) { chapter ->
                    TextButton(enabled = controller?.isCurrentMediaItemSeekable == true, onClick = { controller?.seekTo(chapter.startMs); panel = null }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text((if (currentChapter == chapter) "正在收听 · " else "") + chapter.title)
                            Text(audioTime(chapter.startMs) + (chapter.endMs?.let { " — ${audioTime(it)}" } ?: ""), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } }
                "倍速" -> listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { speed -> TextButton(onClick = { controller?.setPlaybackSpeed(speed); panel = null }) { Text("$speed×") } }
                "睡眠" -> (listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", -1 to "当前轨道结束") + if (currentChapter?.endMs != null) listOf(-2 to "当前章节结束") else emptyList()).forEach { (minutes, title) -> TextButton(onClick = { controller?.let { AudiobookController.sleep(it, minutes) }; panel = null }) { Text(title) } }
                "队列" -> LazyColumn(Modifier.heightIn(max = 480.dp)) { items(state.queue.size, key = { index -> state.queue[index].mediaMetadata.extras?.getString("shadow.audio.entry") ?: index }) { index ->
                    val media = state.queue[index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { controller?.seekToDefaultPosition(index); controller?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }; panel = null }, modifier = Modifier.weight(1f)) {
                            Text((if (index == state.currentIndex) "正在收听 · " else "") + (media.mediaMetadata.title?.toString() ?: "轨道 ${index + 1}"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { controller?.moveMediaItem(index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "上移") }
                        IconButton(onClick = { controller?.moveMediaItem(index, index + 1) }, enabled = index < state.queue.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "下移") }
                        IconButton(onClick = { controller?.removeMediaItem(index) }) { Icon(Icons.Rounded.Close, "从队列移除") }
                    }
                } }
                "书签" -> state.id?.let { id ->
                    val marks by library.dao.annotations(id).collectAsState(emptyList())
                    LazyColumn(Modifier.heightIn(max = 480.dp)) { items(marks, key = { it.id }) { mark -> Row {
                        TextButton(onClick = { controller?.seekTo(runCatching { JSONObject(mark.locatorJson).optLong("positionMs") }.getOrDefault(0)); panel = null }, Modifier.weight(1f)) { Text(mark.note) }
                        IconButton(onClick = { scope.launch { library.dao.removeAnnotation(mark.id) } }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") }
                    } } }
                }
            }
        }
    }
}
