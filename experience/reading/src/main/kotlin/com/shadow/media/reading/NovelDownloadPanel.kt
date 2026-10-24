@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun NovelDownloadPanel(repository: NovelRepository, connection: CatalogConnection?, scope: String,
    workId: String, versionId: String, currentOrdinal: Int?) {
    val coroutine = rememberCoroutineScope()
    val flow = remember(scope, workId, versionId) { repository.downloads.observe(scope, workId, versionId) }
    val task by flow.collectAsState(null)
    var wifi by rememberSaveable { mutableStateOf(true) }
    var start by rememberSaveable { mutableStateOf(((currentOrdinal ?: 0) + 1).toString()) }
    var end by rememberSaveable { mutableStateOf("") }
    var custom by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var directory by remember(workId, versionId) { mutableStateOf<JSONObject?>(null) }
    val updates by repository.cache.changes.collectAsState()
    LaunchedEffect(scope, workId, versionId, updates) { directory = repository.cache.savedDirectory(scope, workId, versionId) }
    val online = connection != null && !LibraryResources.offlineOnly
    val first = start.toIntOrNull()?.takeIf { it > 0 }
    val last = end.toIntOrNull()?.takeIf { it >= (first ?: 1) }
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        coroutine.launch {
            busy = true; message = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "下载操作失败，请重试" }
            finally { busy = false }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("缓存与下载", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = online && !busy, onClick = { execute {
                    directory = repository.cache.directory(requireNotNull(connection), workId, versionId, refresh = true)
                    message = "完整目录已缓存，可离线查找章节"
                } }) { Text(if (directory == null) "缓存完整目录" else "刷新目录缓存") }
            }
            Text("下载保存在本机，离开阅读页后继续；卷标题自动跳过。", style = MaterialTheme.typography.bodySmall)
            directory?.let { Text("已缓存 ${it.optInt("total")} 节目录", style = MaterialTheme.typography.labelSmall) }
            task?.let { value -> NovelDownloadProgress(value, !busy, online,
                onPause = { execute { repository.downloads.pause(value) } },
                onResume = { execute { repository.downloads.resume(value) } }) }

            if (task?.active != true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wifi, { wifi = it }, enabled = !busy)
                    Text("仅 Wi-Fi 下载", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = online && !busy, onClick = { execute {
                        repository.downloads.start(scope, workId, versionId, directory?.optString("title") ?: "小说", wifiOnly = wifi)
                    } }) { Text("下载全本") }
                    TextButton(enabled = online && !busy, onClick = { execute {
                        val ordinal = currentOrdinal ?: 0
                        repository.downloads.start(scope, workId, versionId, directory?.optString("title") ?: "小说", ordinal, ordinal + 50, wifi)
                    } }) { Text(if (currentOrdinal == null) "缓存前 50 节" else "从当前缓存 50 节") }
                    TextButton(onClick = { custom = !custom }) { Text("选择范围") }
                }
                if (custom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(start, { start = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = { Text("起始序号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(end, { end = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = { Text("结束序号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton(enabled = online && !busy && first != null && last != null, onClick = { execute {
                            repository.downloads.start(scope, workId, versionId, directory?.optString("title") ?: "小说", requireNotNull(first) - 1, requireNotNull(last), wifi)
                        } }) { Text("缓存") }
                    }
                    Text("按目录中的序号选择，包含起止章节。", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}


@Composable
internal fun NovelDownloadProgress(value: NovelDownloadTask, enabled: Boolean, online: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val status = when (value.state) {
            "queued" -> if (value.wifiOnly) "等待 Wi-Fi 或系统调度" else "等待网络或系统调度"
            "running" -> "正在下载到本机"
            "paused" -> "下载已暂停"
            "partial" -> "部分章节失败"
            "completed" -> if (value.fullBook && value.versionComplete) "全本已下载" else "所选目录已下载"
            else -> "下载未完成"
        }
        Text("$status · ${value.completed} / ${value.total} 章" + if (value.failed > 0) " · ${value.failed} 章失败" else "", style = MaterialTheme.typography.bodySmall)
        if (value.total > 0) LinearProgressIndicator(progress = { value.completed.toFloat() / value.total }, modifier = Modifier.fillMaxWidth())
        if (value.fullBook && value.state == "completed" && !value.versionComplete) Text("书源目录尚不完整，已下载当前版本全部可读章节。", style = MaterialTheme.typography.bodySmall)
        value.message.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        Row {
            if (value.active) TextButton(enabled = enabled, onClick = onPause) { Text("暂停下载") }
            else if (value.state != "completed") TextButton(enabled = online && enabled, onClick = onResume) { Text(if (value.state == "paused") "继续下载" else "重试未完成章节") }
        }
    }
}
