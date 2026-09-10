@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import top.cylunex.shadowmedia.database.MusicPlaylistEntity
import top.cylunex.shadowmedia.library.LibraryResources

@Composable internal fun PlaylistExportSheet(container: AppContainer, playlist: MusicPlaylistEntity, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val providers = remember { container.catalogs.musicProviders() }
    val exports by remember(playlist.id) { container.playlistExports.dao.exports(playlist.id) }.collectAsStateWithLifecycle(emptyList())
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("导出《${playlist.title}》", style = MaterialTheme.typography.titleLarge)
                Text("只导出目标账号能识别的歌曲，保留顺序和重复曲目。修改本地歌单后再次导出，会创建新的服务器副本。", Modifier.padding(vertical = 12.dp))
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it) }
                if (providers.isEmpty()) Text("请先在来源中连接 Jellyfin 或 OpenSubsonic。")
            }
            items(providers, key = { it.descriptor.id }) { provider ->
                val record = exports.firstOrNull { it.providerId == provider.descriptor.id }
                Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(provider.descriptor.name, style = MaterialTheme.typography.titleMedium)
                    if (record != null) Text(when (record.phase) {
                        "DONE" -> "已导出并核对"; "CREATED" -> "已创建，等待核对"; "UNKNOWN", "CREATING" -> "创建结果未确认，请到服务器核对"; else -> "等待导出"
                    }, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !working && !LibraryResources.offlineOnly, onClick = { scope.launch {
                        working = true
                        try { message = container.playlistExports.export(playlist.id, provider.descriptor.id) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message?.takeUnless { it.contains("http", true) } ?: "导出失败，请检查连接或服务器权限" }
                        finally { working = false }
                    } }) { Text(if (record?.phase == "CREATED") "回读核对" else "导出副本") }
                    if (record?.phase in setOf("UNKNOWN", "CREATING")) {
                        Text("清除本机记录后再次导出会新建副本；请先确认服务器是否已有这份歌单。", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !working, onClick = { scope.launch { container.playlistExports.forget(provider.descriptor.id, playlist.id) } }) { Text("清除本机导出记录") }
                    }
                } }
            }
        }
    }
}
