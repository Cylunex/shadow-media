package top.cylunex.shadowmedia

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.model.UnifiedMediaItem

@Composable
internal fun LocalVideosScreen(container: AppContainer, viewModel: MainViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repository = container.localVideos
    val scope = rememberCoroutineScope()
    val queue by container.videoQueue.queue.collectAsStateWithLifecycle()
    var videos by remember { mutableStateOf(emptyList<UnifiedMediaItem>()) }
    var folders by remember { mutableStateOf(repository.folders()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block(); message = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "无法读取视频" }
            finally { videos = repository.videos(); folders = repository.folders(); busy = false }
        }
    }
    LaunchedEffect(Unit) { videos = repository.videos(); container.videoQueue.restore(videos); run { repository.refresh(); container.videoQueue.restore(repository.videos()) } }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        run {
            uris.forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            repository.addDocuments(uris)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            repository.addFolder(uri)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) run { repository.refresh() } else message = "可以通过选择视频或添加文件夹播放，无需扫描权限"
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("返回") }; Text("本机视频与链接", style = MaterialTheme.typography.headlineMedium) }
        item { Row { TextButton(onClick = onContinue) { Text("继续观看") }; TextButton(enabled = !busy, onClick = { run { repository.refresh() } }) { Text("刷新") } } }
        item { Row {
            TextButton(enabled = !busy, onClick = { permission.launch(when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }) }) { Text("扫描手机") }
            TextButton(enabled = !busy, onClick = { documents.launch(arrayOf("video/*")) }) { Text("选择视频") }
            TextButton(enabled = !busy, onClick = { folder.launch(null) }) { Text("添加文件夹") }
        } }
        item { Text("文件夹内同名 SRT / ASS / VTT 字幕会自动加载。单独选择视频时，可在播放器中手动选择字幕。", style = MaterialTheme.typography.bodySmall) }
        items(folders, key = { it }) { uri -> Row { Text("已添加：${android.net.Uri.decode(uri.substringAfterLast('/'))}", Modifier.weight(1f)); TextButton(enabled = !busy, onClick = { run { repository.removeFolder(uri) } }) { Text("移除") } } }
        item { OutlinedTextField(url, { url = it }, label = { Text("视频链接 / m3u8 直播流") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && url.isNotBlank(), onClick = { run { val video = repository.addLink(url); url = ""; viewModel.playUnifiedItem(video) } }) { Text("播放链接") } }
        item {
            Row {
                TextButton(enabled = videos.isNotEmpty(), onClick = {
                    val selected = videos.filter { it.title.contains(query, true) }
                    if (selected.isNotEmpty()) { val started = container.videoQueue.start(selected); viewModel.playUnifiedItem(selected.first(), started.currentEntryId) }
                }) { Text("按当前列表连续播放") }
                if (queue.current != null) TextButton(onClick = {
                    val restored = container.videoQueue.activate()
                    restored.current?.let { current -> videos.find { it.key == current.key }?.let { viewModel.playUnifiedItem(it.copy(progressMs = restored.positionMs), restored.currentEntryId) } }
                }) { Text("恢复队列") }
            }
        }
        item { OutlinedTextField(query, { query = it }, label = { Text("搜索视频") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(videos.filter { it.title.contains(query, true) }, key = { it.key.stableId }) { video ->
            Card(onClick = {
                scope.launch {
                    val history = container.localMediaState.history(video.key.stableId)
                    val started = container.videoQueue.start(listOf(video))
                    viewModel.playUnifiedItem(video.copy(progressMs = history?.takeUnless { it.completed }?.positionMs ?: 0), started.currentEntryId)
                }
            }) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(video.title); Text(video.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { container.videoQueue.append(video) }) { Text("加入队列") }
                TextButton(onClick = { scope.launch {
                    val siblings = repository.siblings(video.key)
                    val index = siblings.indexOfFirst { it.key == video.key }
                    if (index >= 0) { val started = container.videoQueue.start(siblings, index); viewModel.playUnifiedItem(video, started.currentEntryId) }
                } }) { Text("从此项连续播放目录") }
            } }
        }
        if (videos.isEmpty() && !busy) item { Text("选择视频、添加文件夹，或扫描手机里的视频。") }
    }
}
