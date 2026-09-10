package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.audio.MediaOfflineStore
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.LibraryResources

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun OfflineScreen(container: AppContainer, onBack: () -> Unit, onOpen: (LibraryAssetEntity) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember(container) { Pager(PagingConfig(40, enablePlaceholders = false)) { container.offline.dao.page() }.flow }.collectAsLazyPagingItems()
    var offlineOnly by remember { mutableStateOf(LibraryResources.offlineOnly) }
    val preferences = remember { context.getSharedPreferences("resource_policy", 0) }
    var unmetered by remember { mutableStateOf(preferences.getBoolean("unmetered", true)) }
    var charging by remember { mutableStateOf(preferences.getBoolean("charging", false)) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "离线操作失败" } } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Row { TextButton(onClick = onBack) { Text("返回") }; Text("离线管理", Modifier.padding(12.dp), style = MaterialTheme.typography.headlineSmall) }
        Row {
            Column(Modifier.weight(1f)) { Text("仅离线"); Text("播放已保存内容，暂停网络下载、目录刷新和同步。", style = MaterialTheme.typography.bodySmall) }
            Switch(offlineOnly, onCheckedChange = { enabled ->
                offlineOnly = enabled; LibraryResources.offlineOnly = enabled
                context.getSharedPreferences("resource_policy", 0).edit().putBoolean("offlineOnly", enabled).apply()
                MediaOfflineStore.get(context).setOfflineMode(enabled)
            })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("新任务仅使用非计费网络", Modifier.weight(1f))
            Switch(unmetered, { unmetered = it; preferences.edit().putBoolean("unmetered", it).apply() })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("新任务仅在充电时下载", Modifier.weight(1f))
            Switch(charging, { charging = it; preferences.edit().putBoolean("charging", it).apply() })
        }
        Text("离线副本不会被普通缓存清理淘汰。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { run { val bytes = container.offline.cleanTemporaryFiles(); message = "已清理 ${bytes / 1024 / 1024} MiB 过期临时文件" } }) { Text("清理中断任务的临时文件") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tasks.itemCount == 0) item { Text("还没有离线任务。可在作品或歌曲选项中选择保存离线副本。") }
            items(tasks.itemCount, key = tasks.itemKey { it.assetId }, contentType = { "offline-task" }) { index ->
                val task = tasks[index] ?: return@items
                var asset by remember(task.assetId) { mutableStateOf<LibraryAssetEntity?>(null) }
                LaunchedEffect(task.assetId, task.revision) { asset = container.library.dao.asset(task.assetId) }
                Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(asset?.title ?: "作品已移除", style = MaterialTheme.typography.titleMedium)
                    Text(when (task.state) { "OFFLINE_AVAILABLE" -> "可离线使用"; "OFFLINE_PENDING" -> "排队或下载中"; "OFFLINE_PAUSED" -> "已暂停"; "OFFLINE_STALE" -> "版本已变化"; else -> "下载失败" })
                    if (task.totalBytes > 0) LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${task.bytes / 1024 / 1024} MiB" + if (task.totalBytes > 0) " / ${task.totalBytes / 1024 / 1024} MiB" else "", style = MaterialTheme.typography.bodySmall)
                    if (task.message.isNotBlank()) Text(task.message, style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        if (task.state == "OFFLINE_AVAILABLE") TextButton(enabled = asset != null, onClick = { asset?.let(onOpen) }) { Text("打开") }
                        else if (task.state == "OFFLINE_PENDING") TextButton(onClick = { run { container.offline.pause(task) } }) { Text("暂停") }
                        else TextButton(enabled = !offlineOnly, onClick = { run { container.offline.retry(task) } }) { Text("继续/重试") }
                        TextButton(onClick = { run { container.offline.remove(task) } }) { Text("移除离线副本") }
                    }
                } }
            }
        }
    }
}
