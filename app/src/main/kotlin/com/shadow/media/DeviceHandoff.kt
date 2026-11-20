package com.shadow.media

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shadow.media.library.*
import com.shadow.media.model.MediaKey
import com.shadow.media.model.accountScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

internal data class DevicePlayback(val key: MediaKey, val title: String, val position: Long) {
    fun json() = JSONObject().put("source", key.providerId).put("item", key.itemId)
        .put("title", title.take(240).ifBlank { "视频" }).put("position", position.coerceIn(0L, 2592000000L))
    companion object {
        fun parse(json: JSONObject) = DevicePlayback(MediaKey(json.getString("source"), json.getString("item")),
            json.getString("title"), json.getLong("position"))
    }
}
internal data class MediaDevice(val id: String, val name: String, val online: Boolean, val playback: DevicePlayback?)

internal fun portableMdc(connection: CatalogConnection, tvbox: Boolean): String {
    val owner = if (connection.isSharedMdc()) connection.username else connection.username + "\u0000" + connection.token
    return accountScope(if (tvbox) "mdc-tvbox" else "mdc", connection.base().toString(), "mdc", owner)
}

internal fun MediaKey.localMdcKey(connections: List<CatalogConnection>): MediaKey? {
    val tvbox = providerId.startsWith("mdc-tvbox:")
    if (!tvbox && !providerId.startsWith("mdc:")) return null
    val connection = connections.singleOrNull { portableMdc(it, tvbox) == providerId } ?: return null
    return copy(providerId = (if (tvbox) "mdc-tvbox:" else "mdc:") + connection.id)
}

internal fun CatalogConnection.handoffAccount() = accountScope("handoff", base().toString(), oidcIssuer, oidcSubject, oidcClientId)

internal class DeviceHandoff(context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences("media_device_handoff", Context.MODE_PRIVATE)
    private val api = NovelApi(bearer = container.novel.oidc)
    var accountId by mutableStateOf(prefs.getString("account", null))
        private set
    var devices by mutableStateOf(emptyList<MediaDevice>())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    @Volatile private var latest: DevicePlayback? = null
    fun accounts() = container.novel.availableConnections().filter { it.oidcIssuer.isNotEmpty() && it.oidcSubject.isNotBlank() && it.oidcAuthState.isNotBlank() }.distinctBy { it.handoffAccount() }
    fun select(connection: CatalogConnection?) {
        val id = connection?.handoffAccount()
        if (id == accountId) return
        latest = null; devices = emptyList(); message = null
        prefs.edit().putString("account", id).apply()
        accountId = id
    }

    fun playback(source: String, item: String, title: String, position: Long): DevicePlayback? {
        val wire = when (val p = container.providerRegistry.provider(source)) {
            is MdcTvBoxProvider -> portableMdc(p.connection, true)
            is MdcMediaProvider -> portableMdc(p.connection, false)
            else -> source.takeIf { it.startsWith("emby:") || it.startsWith("tvbox:") } ?: return null
        }
        if (!Regex("[a-zA-Z0-9._:-]{1,480}").matches(item)) return null
        return DevicePlayback(MediaKey(wire, item), title, position)
    }
    fun observe(source: String, item: String, title: String, position: Long) {
        if (accountId != null) latest = playback(source, item, title, position)
    }
    suspend fun resume(row: DevicePlayback, vm: MainViewModel) {
        val key = if (row.key.providerId.startsWith("mdc:") || row.key.providerId.startsWith("mdc-tvbox:"))
            vm.resolveMdcHandoff(row.key) else row.key
        vm.resumeDevicePlayback(key, row.position)
    }
    private fun account(id: String) = accounts().singleOrNull { it.handoffAccount() == id } ?: error("请重新选择已登录的统一账号")
    suspend fun send(device: MediaDevice, playing: DevicePlayback) {
        val id = requireNotNull(accountId)
        val c = withContext(Dispatchers.IO) { account(id) }
        api.json(c, listOf("media-devices", device.id, "handoff"), method = "POST",
            body = JSONObject().put("request_id", UUID.randomUUID().toString()).put("playback", playing.json()))
    }
    suspend fun sync(id: String, vm: MainViewModel) {
        var published: DevicePlayback? = null
        var capable = false
        val identity = container.clientIdentity
        while (currentCoroutineContext().isActive && accountId == id) {
            try {
                val c = withContext(Dispatchers.IO) { account(id) }
                if (!capable) {
                    check(api.json(c, listOf("services")).optInt("media_handoff") == 1) { "当前统一服务尚未支持设备接力" }
                    capable = true
                }
                val now = latest
                val body = JSONObject().put("name", identity.deviceName.take(80))
                if (now != null && now != published) body.put("playback", now.json())
                prefs.getString("handled:" + id, null)?.let { body.put("ack", it) }
                val result = api.json(c, listOf("media-devices", identity.deviceId, "sync"), method = "POST", body = body)
                currentCoroutineContext().ensureActive()
                if (accountId != id) return
                published = now
                devices = result.novelObjects("devices").map { MediaDevice(it.getString("id"), it.getString("name"),
                    it.getBoolean("online"), it.optJSONObject("playback")?.let(DevicePlayback::parse)) }
                message = null
                result.optJSONObject("pending")?.let { incoming ->
                    val command = incoming.getString("id")
                    if (prefs.getString("handled:" + id, null) != command) {
                        try { resume(DevicePlayback.parse(incoming.getJSONObject("playback")), vm) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { vm.deviceHandoffFailed(e) }
                        currentCoroutineContext().ensureActive()
                        prefs.edit().putString("handled:" + id, command).commit()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { devices = emptyList(); message = e.message ?: "设备同步失败" }
            delay(if (message == null) 3000 else 15000)
        }
    }
}

@Composable internal fun ObserveDeviceHandoff(handoff: DeviceHandoff, vm: MainViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val id = handoff.accountId
    LaunchedEffect(id, lifecycle) {
        if (id != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { handoff.sync(id, vm) }
    }
}

@Composable internal fun DeviceHandoffPanel(handoff: DeviceHandoff, vm: MainViewModel?,
    current: (() -> DevicePlayback?)? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(emptyList<CatalogConnection>()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(handoff) {
        try { accounts = withContext(Dispatchers.IO) { handoff.accounts() } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { feedback = e.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("设备接力") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (accounts.isEmpty()) Text("在 Novel 登录统一账号，再连接手机或电视。")
            accounts.forEach { c -> TextButton(onClick = { handoff.select(c); feedback = null }) {
                Text((if (handoff.accountId == c.handoffAccount()) "已启用 · " else "启用 · ") + c.name)
            } }
            if (handoff.accountId != null) {
                TextButton(onClick = { handoff.select(null); feedback = null }) { Text("关闭本机设备同步") }
                if (handoff.devices.isEmpty()) Text(handoff.message ?: "在另一台设备启用同一账号的设备接力。")
                handoff.devices.forEach { d ->
                    Text(d.name + if (d.online) " · 在线" else " · 离线")
                    if (current != null && d.online) TextButton(enabled = !sending, onClick = {
                        scope.launch {
                            sending = true
                            try { handoff.send(d, requireNotNull(current()) { "此来源无法跨设备接力" }); feedback = "已发送到 " + d.name }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { feedback = e.message }
                            finally { sending = false }
                        }
                    }) { Text("发送当前播放") }
                    if (vm != null && d.playback != null) TextButton(onClick = { scope.launch {
                        try { handoff.resume(d.playback, vm); onDismiss() }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { feedback = e.message }
                    } }) { Text("继续「" + d.playback.title + "」 · " + d.playback.position / 1000 + " 秒") }
                }
            }
            feedback?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
