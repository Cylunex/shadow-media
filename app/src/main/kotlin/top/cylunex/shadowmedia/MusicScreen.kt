@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package top.cylunex.shadowmedia

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.audio.*
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.AudioMode

@Composable fun MusicScreen(container: AppContainer, controller: MediaController?, onPlaying: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model: MusicViewModel = viewModel(factory = remember(container) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicViewModel(container) as T
    } })
    val tracks = model.tracks.collectAsLazyPagingItems()
    val albums = model.albums.collectAsLazyPagingItems()
    val artists = model.artists.collectAsLazyPagingItems()
    val folders = model.folders.collectAsLazyPagingItems()
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val filter by model.filter.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.working.collectAsStateWithLifecycle()
    val snapshots by model.snapshotState.collectAsStateWithLifecycle()
    val sources by model.sources.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf("歌曲") }
    var playlistName by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MusicRow?>(null) }
    var addPlaylist by remember { mutableStateOf(false) }
    var exportPlaylist by remember { mutableStateOf<MusicPlaylistEntity?>(null) }
    var editingPlaylist by remember { mutableStateOf<MusicPlaylistEntity?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (uris.isNotEmpty()) model.importFiles(uris)
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION); model.importFolder(it) }
    }
    fun play(ids: List<String>, selected: String) {
        val control = controller ?: run { model.message.value = "音频服务正在连接"; return }
        AudiobookController.play(control, ids, selected, AudioMode.MUSIC); onPlaying()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("最近", "专辑", "艺人", "歌曲", "目录", "歌单", "收藏").forEach { title ->
                FilterChip(section == title, onClick = { section = title; model.filter.value = MusicFilter(recent = title == "最近", favorite = title == "收藏") }, label = { Text(title) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            TextButton(enabled = !busy, onClick = { files.launch(arrayOf("audio/*")) }) { Text("导入歌曲") }
            TextButton(enabled = !busy, onClick = { directory.launch(null) }) { Text("音乐目录") }
            TextButton(enabled = !busy, onClick = model::loadSources) { Text("连接的音乐库") }
            if (section == "歌单") TextButton(onClick = { playlistName = "" }) { Text("新建歌单") }
            TextButton(onClick = { controller?.let { AudiobookController.restoreMode(it, AudioMode.MUSIC) } }) { Text("恢复音乐队列") }
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) { LinearProgressIndicator(Modifier.weight(1f)); TextButton(onClick = model::cancel) { Text("取消") } }
        if (snapshots.any { !it.completed }) Text("部分目录可能过期，正在显示本机快照", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
        if (section !in setOf("专辑", "艺人", "目录", "歌单")) {
            OutlinedTextField(filter.query, onValueChange = { model.filter.value = filter.copy(query = it) }, label = { Text("搜索歌曲、专辑或艺人") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(enabled = tracks.itemCount > 0, onClick = {
                    scope.launch {
                        val ids = model.queueIds()
                        if (ids.isNotEmpty()) play(ids, ids.first())
                    }
                }) { Text("播放当前列表") }
                Text("${tracks.itemCount} 首已加载", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            when (section) {
                "专辑", "艺人", "目录" -> {
                    val groups = when (section) { "专辑" -> albums; "艺人" -> artists; else -> folders }
                    items(groups.itemCount, key = groups.itemKey { it.key }, contentType = { "music-group" }) { index -> groups[index]?.let { group ->
                        ListItem(headlineContent = { Text(group.title.ifBlank { "未标注" }) }, supportingContent = { Text("${group.count} 首") }, modifier = Modifier.clickable {
                            model.filter.value = when (section) { "专辑" -> MusicFilter(album = group.key); "艺人" -> MusicFilter(artist = group.key); else -> MusicFilter(folder = group.key) }; section = "歌曲"
                        })
                    } }
                }
                "歌单" -> items(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                    ListItem(headlineContent = { Text(playlist.title) }, trailingContent = { IconButton(onClick = { editingPlaylist = playlist }) { Icon(Icons.Rounded.Edit, "编辑歌单") } }, modifier = Modifier.clickable { model.filter.value = MusicFilter(playlist = playlist.id); section = "歌曲" })
                }
                else -> {
                    if (tracks.itemCount == 0) item { Text("这里还没有歌曲。导入文件、选择音乐目录或索引已连接的音乐库。", Modifier.padding(20.dp)) }
                    items(tracks.itemCount, key = tracks.itemKey { it.asset.id }, contentType = { "song" }) { index -> tracks[index]?.let { row ->
                        ListItem(headlineContent = { Text(row.asset.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(listOf(row.artist, row.album, row.asset.providerId.substringBefore(':')).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2) },
                            leadingContent = { Icon(if (row.asset.favorite) Icons.Rounded.Favorite else Icons.Rounded.MusicNote, null) }, trailingContent = { IconButton(onClick = { selected = row }) { Icon(Icons.Rounded.MoreVert, "歌曲操作") } },
                            modifier = Modifier.clickable { play(listOf(row.asset.id), row.asset.id) })
                    } }
                }
            }
        }
    }
    if (sources.isNotEmpty()) AlertDialog(onDismissRequest = { model.sources.value = emptyList() }, title = { Text("选择音乐库") }, text = { LazyColumn { items(sources, key = { it.providerId + it.parentId }) { source -> TextButton(onClick = { model.refresh(source) }, enabled = !busy) { Text(source.title) } } } }, confirmButton = { TextButton(onClick = { model.sources.value = emptyList() }) { Text("关闭") } })
    playlistName?.let { name -> AlertDialog(onDismissRequest = { playlistName = null }, title = { Text("新建歌单") }, text = { OutlinedTextField(name, { playlistName = it }, label = { Text("歌单名称") }) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.playlist(name); playlistName = null }) { Text("创建") } }) }
    selected?.let { row -> ModalBottomSheet(onDismissRequest = { selected = null; addPlaylist = false }) {
        Column(Modifier.padding(24.dp)) {
            Text(row.asset.title, style = MaterialTheme.typography.titleLarge)
            if (addPlaylist) {
                if (playlists.isEmpty()) Text("请先在歌单页新建歌单")
                playlists.forEach { playlist -> TextButton(onClick = { model.addToPlaylist(playlist.id, row.asset.id); selected = null; addPlaylist = false }) { Text(playlist.title) } }
            } else {
                TextButton(onClick = { play(listOf(row.asset.id), row.asset.id); selected = null }) { Text("播放") }
                listOf(true to "下一首播放", false to "加入队列").forEach { (next, title) -> TextButton(enabled = controller != null && (controller.mediaItemCount == 0 || controller.currentMediaItem?.audioMode() == AudioMode.MUSIC), onClick = { controller?.let { AudiobookController.enqueue(it, listOf(row.asset.id), next) }; selected = null }) { Text(title) } }
                TextButton(onClick = { model.favorite(row); selected = null }) { Text(if (row.asset.favorite) "取消收藏" else "收藏") }
                TextButton(onClick = { scope.launch {
                    try { container.offline.enqueue(row.asset); model.message.value = "已加入离线任务" }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { model.message.value = e.message ?: "无法保存离线副本" }
                }; selected = null }) { Text("保存离线副本") }
                TextButton(onClick = { addPlaylist = true }) { Text("加入歌单") }
            }
        }
    } }
    exportPlaylist?.let { PlaylistExportSheet(container, it, onDismiss = { exportPlaylist = null }) }
    editingPlaylist?.let { playlist ->
        var entries by remember(playlist.id) { mutableStateOf<List<Pair<MusicPlaylistEntryEntity, LibraryAssetEntity?>>>(emptyList()) }
        LaunchedEffect(playlist.id) { entries = model.playlistItems(playlist.id) }
        ModalBottomSheet(onDismissRequest = { editingPlaylist = null }) {
            Column(Modifier.padding(20.dp)) {
                Text(playlist.title, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { exportPlaylist = playlist; editingPlaylist = null }) { Text("导出歌单副本到服务器") }
                LazyColumn(Modifier.heightIn(max = 480.dp)) { items(entries, key = { it.first.entryId }) { (entry, asset) ->
                    val index = entries.indexOfFirst { it.first.entryId == entry.entryId }
                    ListItem(headlineContent = { Text(asset?.title ?: "来源条目已移除") }, trailingContent = { Row {
                        IconButton(enabled = index > 0, onClick = { scope.launch { val order = entries.map { it.first.entryId }.toMutableList(); order.add(index - 1, order.removeAt(index)); model.dao.reorder(playlist.id, order); entries = model.playlistItems(playlist.id) } }) { Icon(Icons.Rounded.ArrowUpward, "上移") }
                        IconButton(onClick = { scope.launch { model.dao.deleteEntry(entry.entryId); entries = model.playlistItems(playlist.id) } }) { Icon(Icons.Rounded.Close, "从歌单移除") }
                    } })
                } }
                TextButton(onClick = { scope.launch { model.dao.deletePlaylist(playlist.id); editingPlaylist = null } }) { Text("删除歌单") }
            }
        }
    }
}
