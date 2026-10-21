package com.shadow.media

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import com.shadow.media.model.PlaybackEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramSocket
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.shadow.media.model.ExternalMediaEntry
import com.shadow.media.model.PlaybackCoordinator
import com.shadow.media.network.*
import com.shadow.media.playback.MediaCastProxy
import com.shadow.media.playback.castMimeType

data class CastPlaybackState(val deviceName: String? = null, val title: String = "", val connecting: Boolean = false,
    val connected: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null)

/** Single owner across pages. URLs, credentials and device control addresses are never persisted. */
class CastPlaybackController(private val context: Context, private val repository: NetworkStorageRepository,
    private val record: (ExternalMediaEntry, Long, Long?) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private val client = DlnaClient()
    private val mutableState = MutableStateFlow(CastPlaybackState())
    val state = mutableState.asStateFlow()
    private var device: DlnaDevice? = null
    private var entry: ExternalMediaEntry? = null
    private var proxy: MediaCastProxy? = null
    private var polling: Job? = null
    private var transition: Job? = null
    private var generation = 0L
    private var positionOffsetMs = 0L
    private var takingBackKey: String? = null
    private var reportStarted = false
    private val returnedPositions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun returnedPosition(provider: String, item: String): Long? = returnedPositions["$provider:$item"]
    fun takeReturnPosition(provider: String, item: String): Long? {
        takingBackKey = "$provider:$item"
        if (entry?.sourceId == provider && entry?.id == item) stop()
        return returnedPositions.remove("$provider:$item")
    }
    private fun savePosition() { entry?.let { if (takingBackKey != "${it.sourceId}:${it.id}") returnedPositions["${it.sourceId}:${it.id}"] = state.value.positionMs } }
    private var report: (suspend (PlaybackEvent, Long, Boolean) -> Unit)? = null
    private data class Report(val send: suspend (PlaybackEvent, Long, Boolean) -> Unit, val event: PlaybackEvent, val position: Long, val paused: Boolean)
    private val reports = Channel<Report>(Channel.UNLIMITED)
    init { scope.launch(Dispatchers.IO) { for (value in reports) try { value.send(value.event, value.position, value.paused) } catch (e: CancellationException) { throw e } catch (_: Exception) { } } }
    private fun report(event: PlaybackEvent) { report?.let { reports.trySend(Report(it, event, state.value.positionMs, !state.value.playing)) } }
    private val owner: PlaybackCoordinator.Participant = PlaybackCoordinator.process.participant { if (state.value.connecting) stop() else pause() }
    fun start(target: DlnaDevice, media: ExternalMediaEntry, player: Player, positionMs: Long, mimeType: String = castMimeType(media.url), onProgress: (suspend (PlaybackEvent, Long, Boolean) -> Unit)? = null, offsetMs: Long = 0) {
        transition?.cancel(); polling?.cancel()
        val request = ++generation
        player.pause()
        owner.claim()
        mutableState.value = state.value.copy(connecting = true, error = null)
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CastPlaybackService::class.java).putExtra("generation", request))
        transition = scope.launch {
            commands.withLock {
                dispose(sendStop = true, releaseOwner = false)
                if (request != generation) return@withLock
                mutableState.value = CastPlaybackState(target.name, media.title, connecting = true, positionMs = positionMs)
                device = target; entry = media; report = onProgress; positionOffsetMs = offsetMs; takingBackKey = null; reportStarted = false
                savePosition()
                try {
                    val address = withContext(Dispatchers.IO) { InetAddress.getAllByName(target.controlUrl.toHttpUrl().host).first() }
                    val local = withContext(Dispatchers.IO) { DatagramSocket().use { socket -> socket.connect(address, 9); socket.localAddress } }
                    proxy = withContext(Dispatchers.IO) { MediaCastProxy(context, media, local, address, repository, mimeType) }
                    client.setMedia(target, requireNotNull(proxy).url, media.title, mimeType)
                    ensureActive()
                    if (positionMs > offsetMs && !mimeType.contains("mpegurl")) client.seek(target, positionMs - offsetMs)
                    ensureActive()
                    client.play(target)
                    ensureActive()
                    mutableState.value = state.value.copy(connecting = false, connected = true, playing = true)
                    reportStarted = true
                    report(PlaybackEvent.STARTED)
                    poll(request)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { dispose(sendStop = true) }
                    throw e
                } catch (_: Exception) {
                    dispose(sendStop = true)
                    mutableState.value = state.value.copy(connecting = false, connected = false, playing = false,
                        error = "设备无法播放，请检查设备连接及格式支持；本机保持暂停，可手动继续")
                    context.stopService(Intent(context, CastPlaybackService::class.java))
                }
            }
        }
    }
    private fun poll(request: Long) {
        polling?.cancel()
        polling = scope.launch {
            var failures = 0
            var lastReport = 0L
            while (isActive && request == generation) {
                delay(2000)
                commands.withLock {
                    if (request != generation) return@launch
                    val target = device ?: return@launch
                    try {
                        val value = client.position(target)
                        ensureActive()
                        val ownedUri = proxy?.url
                        if (value.trackUri.isNotBlank() && value.trackUri != ownedUri) {
                            dispose(sendStop = false)
                            mutableState.value = state.value.copy(connected = false, playing = false, error = "设备已切换到其他内容，已断开本次控制")
                            context.stopService(Intent(context, CastPlaybackService::class.java)); return@launch
                        }
                        failures = 0
                        mutableState.value = state.value.copy(positionMs = value.positionMs + positionOffsetMs, durationMs = if (value.durationMs > 0) value.durationMs + positionOffsetMs else 0,
                            playing = value.transport == "PLAYING", error = null)
                        savePosition()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastReport >= 10_000) {
                            lastReport = now
                            entry?.let { record(it, state.value.positionMs, state.value.durationMs.takeIf { duration -> duration > 0 }) }
                            report(PlaybackEvent.TIME_UPDATE)
                        }
                        if (value.transport == "STOPPED" || value.transport == "NO_MEDIA_PRESENT") {
                            dispose(sendStop = false)
                            mutableState.value = state.value.copy(connected = false, playing = false)
                            context.stopService(Intent(context, CastPlaybackService::class.java)); return@launch
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (++failures >= 3) {
                            dispose(sendStop = false)
                            mutableState.value = state.value.copy(connected = false, playing = false, error = "设备连接已断开，已保存最后播放位置")
                            context.stopService(Intent(context, CastPlaybackService::class.java)); return@launch
                        }
                    }
                }
            }
        }
    }
    private fun command(action: suspend (DlnaDevice) -> Unit, update: (CastPlaybackState) -> CastPlaybackState) {
        val request = generation
        scope.launch { commands.withLock {
            val target = device ?: return@withLock
            if (request != generation) return@withLock
            try {
                action(target)
                if (request == generation) {
                    val previous = state.value
                    mutableState.value = update(previous).copy(error = null)
                    report(if (previous.playing != state.value.playing) { if (state.value.playing) PlaybackEvent.UNPAUSE else PlaybackEvent.PAUSE } else PlaybackEvent.TIME_UPDATE)
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = state.value.copy(error = "设备没有响应，请重试或停止投屏") }
        } }
    }
    fun play() { owner.claim(); command({ client.play(it) }) { it.copy(playing = true) } }
    fun pause() = command({ client.pause(it); owner.abandon() }) { it.copy(playing = false) }
    fun seek(positionMs: Long) = command({ client.seek(it, (positionMs - positionOffsetMs).coerceAtLeast(0)) }) { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
    fun stop() {
        ++generation; transition?.cancel(); polling?.cancel()
        scope.launch { commands.withLock { dispose(sendStop = true); mutableState.value = CastPlaybackState(); context.stopService(Intent(context, CastPlaybackService::class.java)) } }
    }
    fun serviceDestroyed(expectedGeneration: Long) {
        if (expectedGeneration == generation && (device != null || proxy != null)) stop()
    }
    private suspend fun dispose(sendStop: Boolean, releaseOwner: Boolean = true) {
        val target = device
        device = null
        savePosition()
        if (reportStarted) report(PlaybackEvent.STOPPED)
        reportStarted = false
        report = null
        val previous = entry; entry = null
        previous?.let { record(it, state.value.positionMs, state.value.durationMs.takeIf { d -> d > 0 }) }
        proxy?.close(); proxy = null; if (releaseOwner) owner.abandon()
        if (sendStop && target != null) try { client.stop(target) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
}

internal data class PreparedCastMedia(val entry: ExternalMediaEntry, val positionMs: Long, val mime: String,
    val offsetMs: Long = 0, val onProgress: (suspend (PlaybackEvent, Long, Boolean) -> Unit)? = null)
