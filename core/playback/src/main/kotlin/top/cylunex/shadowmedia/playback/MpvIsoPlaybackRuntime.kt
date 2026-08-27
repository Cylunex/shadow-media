package top.cylunex.shadowmedia.playback

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.fongmi.android.tv.player.iso.IsoSessionManager
import com.fongmi.android.tv.player.iso.IsoSourceStats
import `is`.xyz.mpv.MPVLib
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.PlaybackPlan
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.model.millisecondsToEmbyTicks
import top.cylunex.shadowmedia.network.ClientIdentity
import top.cylunex.shadowmedia.network.PlaybackOutbox
import top.cylunex.shadowmedia.network.PlaybackReport

data class IsoTrack(val id: Int, val name: String)

data class MpvIsoPlaybackState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isSeekable: Boolean = false,
    val bufferPercent: Int = 0,
    val chapterIndex: Int = -1,
    val chapterCount: Int = 0,
    val audioTracks: List<IsoTrack> = emptyList(),
    val selectedAudioTrack: Int = -1,
    val subtitleTracks: List<IsoTrack> = emptyList(),
    val selectedSubtitleTrack: Int = -1,
    val error: String? = null,
)

data class MpvIsoDiagnostics(
    val engine: String = "libmpv + libbluray/libdvdnav",
    val nativeAbi: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hardwareDecoder: String? = null,
    val source: IsoSourceStats = IsoSourceStats(),
    val lastError: String? = null,
)

/**
 * WebHTV-derived GPL-3.0 ISO engine. HTTP bytes are exposed to native libbluray/libdvdnav through
 * `webhtv-dvdiso://`; this preserves disc timelines and makes Blu-ray multi-clip seeks reliable.
 */
class MpvIsoPlaybackRuntime(
    context: Context,
    private val session: EmbySession,
    private val plan: PlaybackPlan,
    private val playbackOutbox: PlaybackOutbox,
    clientIdentity: ClientIdentity,
    private val startPositionMs: Long,
    private val onTerminalError: (positionMs: Long, message: String) -> Unit = { _, _ -> },
) : Closeable, MPVLib.EventObserver {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(
            ScopedPlaybackHeadersInterceptor(
                PlaybackHeaderPolicy(session.serverUrl.toHttpUrl(), session, clientIdentity)
            )
        )
        .build()
    private val stateFlow = MutableStateFlow(
        MpvIsoPlaybackState(
            positionMs = startPositionMs.coerceAtLeast(0L),
            durationMs = plan.runTimeTicks?.embyTicksToMilliseconds()?.coerceAtLeast(0L) ?: 0L,
        )
    )
    private val diagnosticsFlow = MutableStateFlow(MpvIsoDiagnostics())
    private val closed = AtomicBoolean(false)
    private val stoppedReported = AtomicBoolean(false)
    private var surfaceView: SurfaceView? = null
    private var initialized = false
    private var loaded = false
    private var started = false
    private var progressJob: Job? = null
    private var isoUri: String? = null
    private var nativeSurface: Surface? = null

    val state: StateFlow<MpvIsoPlaybackState> = stateFlow.asStateFlow()
    val diagnostics: StateFlow<MpvIsoDiagnostics> = diagnosticsFlow.asStateFlow()

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attachNativeSurface(holder.surface)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (initialized && holder.surface.isValid && nativeSurface !== holder.surface) {
                runCatching { MPVLib.replaceSurface(holder.surface) }
                nativeSurface = holder.surface
            }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (initialized) runCatching { MPVLib.detachSurface() }
            nativeSurface = null
        }
    }

    fun attach(view: SurfaceView) {
        if (closed.get()) return
        surfaceView?.holder?.removeCallback(surfaceCallback)
        surfaceView = view
        view.holder.addCallback(surfaceCallback)
        if (view.holder.surface?.isValid == true) attachNativeSurface(view.holder.surface)
    }

    fun play() {
        if (initialized && !closed.get()) MPVLib.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (initialized && !closed.get()) MPVLib.setPropertyBoolean("pause", true)
    }

    fun seekTo(positionMs: Long) {
        if (!initialized || !stateFlow.value.isSeekable || closed.get()) return
        val target = positionMs.coerceIn(0L, stateFlow.value.durationMs.coerceAtLeast(0L))
        MPVLib.command(arrayOf("seek", String.format(Locale.US, "%.3f", target / 1000.0), "absolute+exact"))
        stateFlow.update { it.copy(positionMs = target) }
        reportCurrent(PlaybackEvent.TIME_UPDATE)
    }

    fun nextAudioTrack() {
        if (initialized) MPVLib.command(arrayOf("cycle", "aid"))
    }

    fun nextSubtitleTrack() {
        if (initialized) MPVLib.command(arrayOf("cycle", "sid"))
    }

    fun previousChapter() {
        if (initialized) MPVLib.command(arrayOf("add", "chapter", "-1"))
    }

    fun nextChapter() {
        if (initialized) MPVLib.command(arrayOf("add", "chapter", "1"))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        progressJob?.cancel()
        val finalPosition = stateFlow.value.positionMs
        val shouldReport = started && stoppedReported.compareAndSet(false, true)
        surfaceView?.holder?.removeCallback(surfaceCallback)
        surfaceView = null
        if (initialized) {
            runCatching { MPVLib.command(arrayOf("stop")) }
            MPVLib.removeObserver(this)
            runCatching { MPVLib.detachSurface() }
            runCatching { MPVLib.destroyCreatedContext() }
        }
        nativeSurface = null
        initialized = false
        IsoSessionManager.closeUri(isoUri)
        isoUri = null
        playbackClient.dispatcher.cancelAll()
        if (shouldReport) {
            scope.launch(Dispatchers.IO) {
                runCatching { report(PlaybackEvent.STOPPED, finalPosition, paused = true) }
                scope.cancel()
            }
        } else scope.cancel()
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "chapter" -> stateFlow.update { it.copy(chapterIndex = value.toInt()) }
            "chapters" -> stateFlow.update { it.copy(chapterCount = value.toInt()) }
            "cache-buffering-state" -> stateFlow.update { it.copy(bufferPercent = value.toInt().coerceIn(0, 100)) }
            "track-list/count" -> refreshTracks()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                val wasPlaying = stateFlow.value.isPlaying
                stateFlow.update { it.copy(isPlaying = !value) }
                if (started && wasPlaying && value) reportCurrent(PlaybackEvent.PAUSE)
                if (started && !wasPlaying && !value) reportCurrent(PlaybackEvent.UNPAUSE)
            }
            "paused-for-cache" -> stateFlow.update { it.copy(isBuffering = value) }
            "seekable" -> stateFlow.update { it.copy(isSeekable = value) }
        }
    }

    override fun eventProperty(property: String, value: String?) {
        if (property == "aid" || property == "sid") refreshTracks()
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos", "time-pos/full" -> stateFlow.update {
                it.copy(positionMs = (value * 1000).toLong().coerceAtLeast(0L))
            }
            "duration", "duration/full" -> stateFlow.update {
                it.copy(durationMs = maxOf(it.durationMs, (value * 1000).toLong()))
            }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                loaded = true
                refreshNativeState()
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                stateFlow.update { it.copy(isBuffering = false, isPlaying = !nativeBoolean("pause", false)) }
                refreshNativeState()
                if (!started) {
                    started = true
                    reportCurrent(PlaybackEvent.STARTED)
                    startProgressLoop()
                }
            }
        }
    }

    override fun endFile(reason: Int, error: Int, errorText: String?) {
        if (closed.get()) return
        if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF) {
            stateFlow.update { it.copy(isPlaying = false, isBuffering = false) }
            reportStoppedOnce()
            return
        }
        if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_STOP) return
        val sourceError = IsoSessionManager.stats(isoUri).lastError
        val message = sanitizeError(sourceError ?: errorText ?: "ISO 原生引擎加载失败 ($error)")
        stateFlow.update { it.copy(isPlaying = false, isBuffering = false, error = message) }
        diagnosticsFlow.update { it.copy(lastError = message) }
        onTerminalError(stateFlow.value.positionMs, message)
    }

    private fun attachNativeSurface(surface: Surface) {
        if (closed.get() || !surface.isValid) return
        try {
            if (!initialized) initialize()
            if (nativeSurface !== surface) {
                if (nativeSurface == null) MPVLib.attachSurface(surface) else MPVLib.replaceSurface(surface)
                nativeSurface = surface
            }
            if (!loaded) loadIso()
        } catch (error: Throwable) {
            fail("ISO 原生引擎初始化失败：${sanitizeError(error.message ?: error.javaClass.simpleName)}")
        }
    }

    private fun initialize() {
        if (!MPVLib.ensureLoaded(appContext)) {
            throw IllegalStateException(MPVLib.getLoadError()?.message ?: "设备 ABI 不受支持")
        }
        if (!MPVLib.tryCreate(appContext)) throw IllegalStateException("原生播放上下文正在被占用")
        setOption("config", "no")
        setOption("vo", "gpu")
        setOption("gpu-context", "android")
        setOption("opengl-es", "yes")
        setOption("hwdec", "mediacodec-copy")
        setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        setOption("ao", "audiotrack")
        setOption("audio-set-media-role", "yes")
        setOption("cache", "yes")
        setOption("cache-on-disk", "no")
        setOption("cache-pause", "yes")
        setOption("cache-pause-initial", "yes")
        setOption("cache-pause-wait", "1.0")
        setOption("demuxer-thread", "yes")
        setOption("demuxer-seekable-cache", "auto")
        setOption("demuxer-max-bytes", (128L * 1024 * 1024).toString())
        setOption("demuxer-max-back-bytes", (32L * 1024 * 1024).toString())
        setOption("sub-ass", "yes")
        setOption("embeddedfonts", "yes")
        setOption("sub-fix-timing", "yes")
        setOption("input-default-bindings", "yes")
        setOption("tls-verify", "yes")
        setOption("msg-level", "all=warn")
        MPVLib.init()
        initialized = true
        MPVLib.addObserver(this)
        MPVLib.setPropertyString("idle", "yes")
        MPVLib.setPropertyBoolean("pause", false)
        observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observe("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observe("seekable", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observe("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("chapters", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        observe("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        diagnosticsFlow.update { it.copy(nativeAbi = MPVLib.getLoadedAbi()) }
    }

    private fun loadIso() {
        IsoSessionManager.configure(playbackClient)
        isoUri = IsoSessionManager.create(plan.primary.url, plan.primary.requiredHeaders)
        val start = startPositionMs.takeIf { it > 0 }?.let {
            "start=${String.format(Locale.US, "%.3f", it / 1000.0)}"
        }
        val command = if (start == null) {
            arrayOf("loadfile", requireNotNull(isoUri), "replace")
        } else {
            arrayOf("loadfile", requireNotNull(isoUri), "replace", "-1", start)
        }
        val result = MPVLib.command(command)
        if (result < 0) throw IllegalStateException("mpv loadfile 返回 $result")
        loaded = true
        startProgressLoop()
    }

    private fun refreshNativeState() {
        if (!initialized || closed.get()) return
        val position = nativeDouble("time-pos", stateFlow.value.positionMs / 1000.0)
        val duration = nativeDouble("duration", stateFlow.value.durationMs / 1000.0)
        stateFlow.update {
            it.copy(
                positionMs = (position * 1000).toLong().coerceAtLeast(0L),
                durationMs = maxOf(it.durationMs, (duration * 1000).toLong()),
                isPlaying = !nativeBoolean("pause", !it.isPlaying),
                isBuffering = nativeBoolean("paused-for-cache", it.isBuffering),
                isSeekable = nativeBoolean("seekable", it.isSeekable),
                chapterIndex = nativeInt("chapter", it.chapterIndex),
                chapterCount = nativeInt("chapters", it.chapterCount),
            )
        }
        refreshTracks()
        diagnosticsFlow.update {
            it.copy(
                videoCodec = nativeString("video-codec", it.videoCodec),
                audioCodec = nativeString("audio-codec", it.audioCodec),
                hardwareDecoder = nativeString("hwdec-current", it.hardwareDecoder),
                source = IsoSessionManager.stats(isoUri),
            )
        }
    }

    private fun refreshTracks() {
        if (!initialized || closed.get()) return
        val audio = mutableListOf<IsoTrack>()
        val subtitles = mutableListOf<IsoTrack>()
        var selectedAudio = -1
        var selectedSubtitle = -1
        val count = nativeInt("track-list/count", 0).coerceIn(0, 128)
        repeat(count) { index ->
            val prefix = "track-list/$index/"
            val type = nativeString(prefix + "type", null)
            val id = nativeInt(prefix + "id", -1)
            val language = nativeString(prefix + "lang", null)
            val title = nativeString(prefix + "title", null)
            val name = listOfNotNull(title, language).distinct().joinToString(" · ").ifBlank { "$type $id" }
            val selected = nativeBoolean(prefix + "selected", false)
            when (type) {
                "audio" -> {
                    audio += IsoTrack(id, name)
                    if (selected) selectedAudio = id
                }
                "sub" -> {
                    subtitles += IsoTrack(id, name)
                    if (selected) selectedSubtitle = id
                }
            }
        }
        stateFlow.update {
            it.copy(
                audioTracks = audio,
                selectedAudioTrack = selectedAudio,
                subtitleTracks = subtitles,
                selectedSubtitleTrack = selectedSubtitle,
            )
        }
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            var reportCountdown = 0
            while (isActive && !closed.get()) {
                delay(1_000L)
                refreshNativeState()
                if (started) {
                    reportCountdown++
                    if (reportCountdown >= 10) {
                        reportCountdown = 0
                        reportCurrent(PlaybackEvent.TIME_UPDATE)
                    }
                }
            }
        }
    }

    private fun reportStoppedOnce() {
        if (!started || !stoppedReported.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching { report(PlaybackEvent.STOPPED, stateFlow.value.positionMs, paused = true) }
        }
    }

    private fun reportCurrent(event: PlaybackEvent) {
        val snapshot = stateFlow.value
        scope.launch(Dispatchers.IO) {
            runCatching { report(event, snapshot.positionMs, paused = !snapshot.isPlaying) }
        }
    }

    private suspend fun report(event: PlaybackEvent, positionMs: Long, paused: Boolean) {
        playbackOutbox.submit(
            session,
            PlaybackReport(
                itemId = plan.itemId,
                mediaSourceId = plan.mediaSourceId,
                playSessionId = plan.playSessionId,
                positionTicks = positionMs.coerceAtLeast(0L).millisecondsToEmbyTicks(),
                isPaused = paused,
                canSeek = stateFlow.value.isSeekable,
                event = event,
                playMethod = plan.primary.method,
            ),
        )
    }

    private fun setOption(name: String, value: String) {
        // The bundled MPV is feature-rich, but keep optional tuning forward-compatible with
        // future native bundles. Failure to create/init/load the context remains fatal.
        runCatching { MPVLib.setOptionString(name, value) }
    }

    private fun observe(name: String, format: Int) {
        MPVLib.observeProperty(name, format)
    }

    private fun nativeString(name: String, fallback: String?): String? =
        runCatching { MPVLib.getPropertyString(name) }.getOrNull() ?: fallback

    private fun nativeInt(name: String, fallback: Int): Int =
        runCatching { MPVLib.getPropertyInt(name) }.getOrNull() ?: fallback

    private fun nativeDouble(name: String, fallback: Double): Double =
        runCatching { MPVLib.getPropertyDouble(name) }.getOrNull() ?: fallback

    private fun nativeBoolean(name: String, fallback: Boolean): Boolean =
        runCatching { MPVLib.getPropertyBoolean(name) }.getOrNull() ?: fallback

    private fun fail(message: String) {
        stateFlow.update { it.copy(isPlaying = false, isBuffering = false, error = message) }
        diagnosticsFlow.update { it.copy(lastError = message, source = IsoSessionManager.stats(isoUri)) }
        onTerminalError(stateFlow.value.positionMs, message)
    }

    private fun sanitizeError(message: String): String = message
        .replace(session.accessToken, "<token>")
        .replace(plan.primary.url, "<播放地址>")
        .replace(Regex("(?:https?|webhtv-dvdiso)://\\S+", RegexOption.IGNORE_CASE), "<地址>")
}
