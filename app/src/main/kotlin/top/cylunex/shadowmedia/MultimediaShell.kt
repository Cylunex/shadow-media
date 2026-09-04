@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia

import android.content.Intent
import android.content.res.Configuration
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import top.cylunex.shadowmedia.audio.*
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.library.LibraryRepository
import top.cylunex.shadowmedia.reading.ComicActivity
import top.cylunex.shadowmedia.reading.ReaderActivity

private val destinations = listOf("影视" to Icons.Rounded.Movie, "直播" to Icons.Rounded.LiveTv, "阅读" to Icons.AutoMirrored.Rounded.MenuBook,
    "听书" to Icons.Rounded.Headphones, "来源" to Icons.Rounded.Storage)

@Composable fun ShadowMediaRoot(viewModel: MainViewModel, container: AppContainer) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assets by container.library.assets.collectAsStateWithLifecycle(emptyList())
    val progress by container.library.progress.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var audioExpanded by rememberSaveable { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var audioState by remember { mutableStateOf(AudioUi()) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val playerScreen = state.screen in setOf(Screen.PLAYER, Screen.EXTERNAL_PLAYER)
    val television = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    DisposableEffect(Unit) {
        val future = AudiobookController.connect(context.applicationContext)
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it } }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }
    LaunchedEffect(controller) {
        while (isActive) {
            controller?.let { audioState = AudioUi(it.currentMediaItem?.mediaId, it.mediaMetadata.title?.toString().orEmpty(), it.isPlaying,
                it.currentPosition.coerceAtLeast(0), it.duration.takeIf { n -> n > 0 } ?: 0, it.playbackParameters.speed, it.playbackState == Player.STATE_BUFFERING,
                it.playerError?.let { "播放失败，请检查文件权限或重新导入。" }) }
            delay(500)
        }
    }
    LaunchedEffect(playerScreen) { if (playerScreen) { controller?.pause(); audioExpanded = false } }
    fun open(item: LibraryAssetEntity) {
        if (item.kind == "AUDIOBOOK") {
            val control = controller
            if (control == null) { message = "音频服务正在连接，请稍后重试"; return }
            scope.launch {
                try {
                    AudiobookController.play(context, control, assets.filter { it.kind == "AUDIOBOOK" }.sortedWith(compareBy { it.title }).map { it.id }, item.id)
                    audioExpanded = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "无法打开音频，请检查文件授权或重新导入" }
            }
        } else {
            val target = if (item.kind == "COMIC" || item.format == "pdf") ComicActivity::class.java else ReaderActivity::class.java
            context.startActivity(Intent(context, target).putExtra("assetId", item.id))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importJob?.cancel()
            importJob = scope.launch {
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
    fun select(index: Int) { tab = index; audioExpanded = false; viewModel.showHome() }
    if (playerScreen) { LegacyMediaRoot(viewModel, container); return }
    BackHandler(enabled = audioExpanded) { audioExpanded = false }
    Scaffold(bottomBar = {
        if (!television && !audioExpanded) Column {
            if (audioState.id != null) AudioMiniBar(audioState, onOpen = { audioExpanded = true }, onToggle = { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
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
            Box(Modifier.weight(1f)) {
                when {
                    audioExpanded -> AudioNowPlaying(audioState, controller, assets, container.library, onBack = { audioExpanded = false })
                    tab == 2 || tab == 3 -> PublicationShelf(assets, progress, audio = tab == 3, onImport = { importer.launch(arrayOf("*/*")) }, onOpen = ::open,
                        onFavorite = { item -> scope.launch { container.library.dao.favorite(item.id, !item.favorite) } },
                        onRemove = { item -> scope.launch {
                            controller?.let { control ->
                                for (i in control.mediaItemCount - 1 downTo 0) if (control.getMediaItemAt(i).mediaId == item.id) control.removeMediaItem(i)
                            }
                            container.library.remove(item.id)
                        } })
                    tab == 4 && state.screen == Screen.HOME -> SourcesHub(state, viewModel, onImport = { importer.launch(arrayOf("*/*")) })
                    tab == 1 && state.screen == Screen.HOME -> LiveLanding(state, container, viewModel)
                    else -> LegacyMediaRoot(viewModel, container)
                }
            }
        }
    }
    importing?.let { label -> AlertDialog(onDismissRequest = {}, title = { Text(label) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { importJob?.cancel() }) { Text("取消") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}

@Composable private fun PublicationShelf(
    assets: List<LibraryAssetEntity>, progress: List<ContentProgressEntity>, audio: Boolean,
    onImport: () -> Unit, onOpen: (LibraryAssetEntity) -> Unit, onFavorite: (LibraryAssetEntity) -> Unit, onRemove: (LibraryAssetEntity) -> Unit,
) {
    var filter by rememberSaveable(audio) { mutableStateOf("全部") }
    var query by rememberSaveable(audio) { mutableStateOf("") }
    var sort by rememberSaveable(audio) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LibraryAssetEntity?>(null) }
    var removing by remember { mutableStateOf<LibraryAssetEntity?>(null) }
    val progressMap = remember(progress) { progress.associateBy { it.assetId } }
    val books = assets.filter { (if (audio) it.kind == "AUDIOBOOK" else it.kind in setOf("BOOK", "COMIC")) &&
        (filter != "小说" || it.kind == "BOOK") && (filter != "漫画" || it.kind == "COMIC") && (filter != "收藏" || it.favorite) &&
        (filter != "未完成" || progressMap[it.id]?.completed != true) && (it.title.contains(query, true) || it.author.contains(query, true)) }
        .let { if (sort) it.sortedBy { item -> item.title } else it }
    val recent = books.filter { progressMap[it.id]?.completed == false }.maxByOrNull { progressMap[it.id]?.updatedAt ?: 0 }
    LazyVerticalGrid(columns = GridCells.Adaptive(if (audio) 144.dp else 116.dp), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(if (audio) "听书" else "阅读", style = MaterialTheme.typography.headlineLarge); Text(if (audio) "让故事陪你走远一点" else "把时间留给下一章", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = onImport) { Icon(Icons.Rounded.Add, "导入文件") }
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
                        Text("${((progressMap[recent.id]?.progression ?: 0.0) * 100).toInt()}% · 进度已保存在本机", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(if (audio) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (audio) "我的有声书" else "我的书架", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { sort = !sort }) { Text(if (sort) "名称排序" else "最近加入") }
        } }
        if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(if (audio) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (query.isNotBlank()) "没有匹配的作品" else if (audio) "从第一本有声书开始" else "这里放得下你的整个书架", style = MaterialTheme.typography.titleMedium)
                Text(if (audio) "导入 M4B、MP3、M4A、FLAC 等音频" else "导入 EPUB、TXT、PDF、CBZ 或 ZIP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onImport) { Text("导入文件") }
            }
        }
        items(books, key = { it.id }) { item -> Column {
            Box(Modifier.clickable { onOpen(item) }) {
                PublicationCover(item, Modifier.fillMaxWidth().aspectRatio(if (audio) 1f else .69f))
                if (item.favorite) Icon(Icons.Rounded.Favorite, "已收藏", Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f).clickable { onOpen(item) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { selected = item }, Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreHoriz, "作品选项") }
            }
            Text(item.author.ifBlank { item.format.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            progressMap[item.id]?.progression?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant) }
        } }
    }
    selected?.let { item -> ModalBottomSheet(onDismissRequest = { selected = null }) {
        Column(Modifier.padding(24.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { selected = null; onOpen(item) }) { Text(if (audio) "开始收听" else "打开阅读") }
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
            Icon(if (item.kind == "AUDIOBOOK") Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.MenuBook, null, tint = Color(0xFFB5C7E0))
            Text(item.title, color = Color.White, maxLines = 4, fontWeight = FontWeight.SemiBold)
            Text(item.format.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB5C7E0))
        }
    }
}

@Composable private fun SourcesHub(state: MainUiState, viewModel: MainViewModel, onImport: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("来源", style = MaterialTheme.typography.headlineLarge); Text("你的内容，按自己的方式连接", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SourceTile("Emby 媒体服务", "${state.savedSessions.size} 个已保存账号", Icons.Rounded.Dns, viewModel::showServers) }
        item { SourceTile("网络存储", "OpenList / WebDAV / SMB · ${state.networkStorages.size} 个连接", Icons.Rounded.FolderOpen, viewModel::showNetworkStorages) }
        item { SourceTile("影视与直播订阅", "${state.externalSources.size} 个订阅 · 导入、更新与诊断", Icons.Rounded.LiveTv, viewModel::showSources) }
        item { SourceTile("本地书籍与音频", "系统文件选择器 · 不需要全盘存储权限", Icons.Rounded.FileOpen, onImport) }
        item { SourceTile("聚合搜索", "搜索已连接的内容服务", Icons.Rounded.Search, viewModel::showDiscover) }
        item { SourceTile("设置与诊断", "功能开关、播放记录与问题排查", Icons.Rounded.Tune, viewModel::showSettings) }
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

private data class AudioUi(val id: String? = null, val title: String = "", val playing: Boolean = false, val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val buffering: Boolean = false, val error: String? = null)
private fun audioTime(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) }

@Composable private fun AudioMiniBar(state: AudioUi, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column { Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${audioTime(state.position)} · ${state.speed}×", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = onToggle) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "继续收听") }
        }; if (state.duration > 0) LinearProgressIndicator(progress = { (state.position.toFloat() / state.duration).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp)) }
    }
}

@Composable private fun AudioNowPlaying(state: AudioUi, controller: MediaController?, assets: List<LibraryAssetEntity>, library: LibraryRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val item = assets.firstOrNull { it.id == state.id }
    val sleep by AudioSleep.remainingMs.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<String?>(null) }
    var drag by remember(state.id) { mutableStateOf<Float?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }; Text("正在收听", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton(onClick = { panel = "队列" }) { Icon(Icons.Rounded.QueueMusic, "播放队列") } } }
        item { if (item != null) PublicationCover(item, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)) else Icon(Icons.Rounded.Headphones, null, Modifier.size(140.dp)) }
        item { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(item?.title ?: state.title.ifBlank { "选择一本有声书" }, style = MaterialTheme.typography.headlineSmall, maxLines = 3); Text(item?.author.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { Column(Modifier.fillMaxWidth()) {
            Slider(value = drag ?: if (state.duration > 0) state.position.toFloat() / state.duration else 0f, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { controller?.seekTo((it * state.duration).toLong()) }; drag = null }, enabled = state.duration > 0 && controller?.isCurrentMediaItemSeekable == true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(audioTime(state.position)); Text(audioTime(state.duration)) }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = controller?.hasPreviousMediaItem() == true) { Icon(Icons.Rounded.SkipPrevious, "上一轨") }
            IconButton(onClick = { controller?.seekTo((state.position - 15000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay10, "后退 15 秒") }
            FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, Modifier.size(76.dp)) { if (state.buffering) CircularProgressIndicator(Modifier.size(30.dp)) else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "播放", Modifier.size(42.dp)) }
            IconButton(onClick = { controller?.seekTo((state.position + 30000).coerceAtMost(state.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }) { Icon(Icons.Rounded.Forward30, "前进 30 秒") }
            IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = controller?.hasNextMediaItem() == true) { Icon(Icons.Rounded.SkipNext, "下一轨") }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { panel = "倍速" }) { Text("${state.speed}× 倍速") }
            TextButton(onClick = { panel = "睡眠" }) { Text(if (sleep == -1L) "本轨结束" else if (sleep > 0) audioTime(sleep) else "睡眠定时") }
            TextButton(onClick = { scope.launch { state.id?.let { library.bookmark(it, JSONObject().put("positionMs", state.position).toString(), audioTime(state.position)) }; panel = "书签" } }) { Text("标记") }
        } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { TextButton(onClick = { panel = "书签" }) { Text("查看所有书签") } }
    }
    if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null }) {
        Column(Modifier.padding(24.dp)) {
            Text(panel!!, style = MaterialTheme.typography.titleLarge)
            when (panel) {
                "倍速" -> listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { speed -> TextButton(onClick = { controller?.setPlaybackSpeed(speed); panel = null }) { Text("$speed×") } }
                "睡眠" -> listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", -1 to "当前轨道结束").forEach { (minutes, title) -> TextButton(onClick = { controller?.let { AudiobookController.sleep(it, minutes) }; panel = null }) { Text(title) } }
                "队列" -> LazyColumn(Modifier.heightIn(max = 480.dp)) { items(controller?.mediaItemCount ?: 0) { index ->
                    val media = controller?.getMediaItemAt(index)
                    TextButton(onClick = { controller?.seekToDefaultPosition(index); controller?.play(); panel = null }) { Text(media?.mediaMetadata?.title?.toString() ?: assets.firstOrNull { it.id == media?.mediaId }?.title ?: "轨道 ${index + 1}") }
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
