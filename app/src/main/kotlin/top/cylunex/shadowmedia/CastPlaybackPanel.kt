package top.cylunex.shadowmedia

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.*
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.network.*
import top.cylunex.shadowmedia.playback.castMimeType

@Composable internal fun CastPlaybackPanel(container: AppContainer, entry: ExternalMediaEntry, player: Player,
    position: () -> Long = { player.currentPosition }, mime: String = castMimeType(entry.url), onDismiss: () -> Unit,
    onProgress: (suspend (PlaybackEvent, Long, Boolean) -> Unit)? = null, onTransfer: () -> Unit = {}, offsetMs: Long = 0, prepare: (suspend () -> PreparedCastMedia)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { DlnaClient() }
    var devices by remember { mutableStateOf(emptyList<DlnaDevice>()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<Job?>(null) }
    fun discover() {
        search?.cancel()
        search = scope.launch {
            searching = true; message = null
            val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("shadow-dlna")
            try { lock?.acquire(); devices = client.discover(); if (devices.isEmpty()) message = "没有发现设备，请确认同一局域网或输入设备描述地址" }
            catch (e: CancellationException) { throw e } catch (_: Exception) { message = "设备发现失败，请检查局域网连接" }
            finally { if (lock?.isHeld == true) lock.release(); searching = false }
        }
    }
    LaunchedEffect(Unit) { discover() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("投屏到 DLNA 设备") }, text = {
        Column {
            Text("手机作为媒体桥接保持运行，设备只接收临时媒体地址。支持的音视频格式以播放设备为准。", style = MaterialTheme.typography.bodySmall)
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = ::discover, enabled = !searching) { Text("重新发现") }
            LazyColumn(Modifier.heightIn(max = 260.dp)) { items(devices, key = { it.id }) { device ->
                TextButton(enabled = !searching, onClick = { scope.launch {
                    searching = true
                    try {
                        val selected = prepare?.invoke() ?: PreparedCastMedia(entry, position().coerceAtLeast(0), mime, offsetMs, onProgress)
                        ensureActive()
                        onTransfer()
                        container.castPlayback.start(device, selected.entry, player, selected.positionMs, selected.mime, selected.onProgress, selected.offsetMs)
                        onDismiss()
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "播放资源解析失败，请刷新来源后重试" }
                    finally { searching = false }
                } }) { Text(device.name) }
            } }
            OutlinedTextField(address, { address = it }, label = { Text("设备描述 URL（可选）") }, singleLine = true)
            TextButton(enabled = address.isNotBlank() && !searching, onClick = { scope.launch {
                try { val device = client.describe(address.trim()); devices = (devices + device).distinctBy { it.id }; message = null }
                catch (e: CancellationException) { throw e } catch (_: Exception) { message = "无法读取局域网设备描述" }
            } }) { Text("添加设备") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable internal fun CastPlaybackBar(container: AppContainer) {
    val cast = container.castPlayback
    val state by cast.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    if (!state.connected && !state.connecting && state.error == null) return
    Surface(tonalElevation = 4.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.weight(1f)) { Text(if (state.connecting) "正在连接播放设备…" else "${state.deviceName.orEmpty()} · ${state.title}", maxLines = 1) }
        if (state.connected) TextButton(onClick = { if (state.playing) cast.pause() else cast.play() }) { Text(if (state.playing) "暂停" else "播放") }
        TextButton(onClick = cast::stop) { Text("停止") }
    } }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(state.title.ifBlank { "远端播放" }) }, text = {
        Column {
            Text(state.deviceName.orEmpty())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("${dlnaTime(state.positionMs)} / ${dlnaTime(state.durationMs)}")
            if (state.durationMs > 0) {
                var target by remember(state.positionMs) { mutableFloatStateOf(state.positionMs.toFloat()) }
                Slider(target.coerceIn(0f, state.durationMs.toFloat()), { target = it }, onValueChangeFinished = { cast.seek(target.toLong()) }, valueRange = 0f..state.durationMs.toFloat())
            }
            Row {
                TextButton(enabled = state.connected, onClick = { cast.seek((state.positionMs - 10_000).coerceAtLeast(0)) }) { Text("快退 10 秒") }
                TextButton(enabled = state.connected, onClick = { cast.seek((state.positionMs + 10_000).coerceAtMost(state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) }) { Text("快进 10 秒") }
            }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } }, dismissButton = { TextButton(onClick = { cast.stop(); expanded = false }) { Text("停止投屏") } })
}
