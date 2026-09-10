@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package top.cylunex.shadowmedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*

@Composable internal fun ContinueScreen(container: AppContainer, onBack: () -> Unit, onOpen: (LibraryAssetEntity) -> Unit, onLegacy: (ContinueRow) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val dao = remember { ShadowMediaDatabase.create(context).libraryStateDao() }
    val scope = rememberCoroutineScope()
    val rows = remember(dao) { Pager(PagingConfig(30, enablePlaceholders = false)) { dao.continuing() }.flow }.collectAsLazyPagingItems()
    var association by remember { mutableStateOf<LibraryAssetEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Row { TextButton(onClick = onBack) { Text("返回") }; Text("继续播放与阅读", Modifier.padding(12.dp), style = MaterialTheme.typography.headlineSmall) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (rows.itemCount == 0) item { Text("打开内容后，继续位置会显示在这里。") }
            items(rows.itemCount, key = rows.itemKey { it.rowId }) { index ->
                val row = rows[index] ?: return@items
                val fraction = row.progression
                val assetId = row.assetId
                Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(when (row.kind) { "BOOK" -> "阅读"; "COMIC" -> "漫画"; "AUDIOBOOK" -> "听书"; "MUSIC" -> "最近收听"; "PODCAST" -> "播客"; else -> "影视" }, style = MaterialTheme.typography.labelMedium)
                    val source = container.providerRegistry.provider(row.providerId)?.descriptor?.name
                        ?: if (row.providerId == "local") "本机" else "已保存来源"
                    Text(source, style = MaterialTheme.typography.bodySmall)
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Text("${(fraction.coerceIn(0.0, 1.0) * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    } else if (row.kind != "MUSIC") Text("总进度未知 · 可恢复已保存位置", style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = {
                            if (assetId == null) onLegacy(row)
                            else scope.launch {
                                val asset = container.library.dao.asset(assetId)
                                if (asset != null) onOpen(asset) else message = "条目已移除"
                            }
                        }) { Text(if (row.kind == "MUSIC") "播放" else "继续") }
                        if (assetId != null) TextButton(onClick = { scope.launch { association = container.library.dao.asset(assetId) } }) { Text("作品与其他版本") }
                    }
                } }
            }
        }
    }
    association?.let { asset -> WorkAssociationSheet(container, asset, onDismiss = { association = null }, onOpen = { association = null; onOpen(it) }) }
}

@Composable internal fun WorkAssociationSheet(container: AppContainer, asset: LibraryAssetEntity, onDismiss: () -> Unit, onOpen: (LibraryAssetEntity) -> Unit) {
    val context = LocalContext.current
    val dao = remember { ShadowMediaDatabase.create(context).libraryStateDao() }
    val scope = rememberCoroutineScope()
    var workId by remember(asset.id) { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(asset.id) { workId = dao.rendition(asset.id)?.workId }
    val editions by remember(workId) { workId?.let(dao::editions) ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val candidates = remember(query) { Pager(PagingConfig(30, enablePlaceholders = false)) {
        container.library.dao.shelf(ContentKind.entries.map { it.name }, "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", false, false, true)
    }.flow }.collectAsLazyPagingItems()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("作品与版本", style = MaterialTheme.typography.titleLarge)
            Text("${asset.title} · ${asset.format.uppercase()}", style = MaterialTheme.typography.titleMedium)
            Text("关联只把版本放在一起；每个版本保留各自的阅读或播放位置。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow {
                TextButton(onClick = { selecting = !selecting }) { Text(if (selecting) "返回已关联版本" else "关联已有版本") }
                if (workId != null) TextButton(onClick = { scope.launch {
                    dao.unlink(asset.id); dao.cleanEmptyWorks(); workId = null
                } }) { Text("解除当前版本关联") }
            }
            if (selecting) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索已加入书库的版本") })
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates.itemCount, key = candidates.itemKey { it.asset.id }) { index ->
                        val target = candidates[index]?.asset ?: return@items
                        TextButton(enabled = target.id != asset.id, onClick = { scope.launch {
                            try {
                                dao.associate(asset.id, target.id, target.title)
                                dao.collect(asset.id); dao.collect(target.id)
                                workId = dao.rendition(asset.id)?.workId; selecting = false
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "关联失败，条目可能已经移除" }
                        } }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) { Text(target.title); Text("${target.kind} · ${target.format.uppercase()}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(editions, key = { it.id }) { edition ->
                        TextButton(onClick = { onOpen(edition) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) { Text(edition.title); Text(edition.format.uppercase(), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (editions.isEmpty()) item { Text("尚未关联其他版本") }
                }
            }
        }
    }
}
