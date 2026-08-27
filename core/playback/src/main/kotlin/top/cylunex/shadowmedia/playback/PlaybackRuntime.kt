package top.cylunex.shadowmedia.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.PlaybackPlan
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.model.millisecondsToEmbyTicks
import top.cylunex.shadowmedia.network.ClientIdentity
import top.cylunex.shadowmedia.network.PlaybackOutbox
import top.cylunex.shadowmedia.network.PlaybackReport

data class PlaybackDiagnostics(
    val method: PlayMethod,
    val candidateIndex: Int,
    val candidateCount: Int,
    val requestHost: String,
    val container: String?,
    val videoType: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val sourceCount: Int,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val supportsTranscoding: Boolean,
    val lastError: String? = null,
)

class PlaybackRuntime(
    context: Context,
    private val session: EmbySession,
    private val plan: PlaybackPlan,
    private val playbackOutbox: PlaybackOutbox,
    clientIdentity: ClientIdentity,
    startPositionMs: Long,
    private val onTerminalError: (positionMs: Long, message: String) -> Unit = { _, _ -> },
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(
            ScopedPlaybackHeadersInterceptor(
                PlaybackHeaderPolicy(session.serverUrl.toHttpUrl(), session, clientIdentity)
            )
        )
        .build()
    private var candidateIndex = 0
    private var positionOffsetMs = 0L
    private var started = false
    private var progressJob: Job? = null
    private var released = false
    private val diagnosticsState = MutableStateFlow(diagnostics())

    val diagnostics: StateFlow<PlaybackDiagnostics> = diagnosticsState.asStateFlow()
    private val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        repeatMode = Player.REPEAT_MODE_ONE
    }
    val player: Player = exoPlayer
    val currentPositionMs: Long
        get() = (positionOffsetMs + player.currentPosition).coerceAtLeast(0L)
    val durationMs: Long
        get() = plan.runTimeTicks?.embyTicksToMilliseconds()?.takeIf { it > 0 }
            ?: (positionOffsetMs + player.duration).coerceAtLeast(0L)
    val isSeekSupported: Boolean
        get() = player.isCurrentMediaItemSeekable ||
            currentCandidate().method == PlayMethod.TRANSCODE ||
            transcodingCandidateIndex() >= 0

    init {
        exoPlayer.addListener(PlayerListener())
        prepareCandidate(plan.primary, startPositionMs.coerceAtLeast(0L), playWhenReady = true)
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        if (currentCandidate().method == PlayMethod.TRANSCODE) {
            prepareCandidate(currentCandidate(), target, player.playWhenReady)
        } else if (player.isCurrentMediaItemSeekable) {
            exoPlayer.seekTo(target)
        } else {
            val transcodeIndex = transcodingCandidateIndex()
            if (transcodeIndex < 0) return
            candidateIndex = transcodeIndex
            diagnosticsState.value = diagnostics("直链不支持拖动，已切换 Emby 转码")
            prepareCandidate(currentCandidate(), target, player.playWhenReady)
        }
        reportCurrent(PlaybackEvent.TIME_UPDATE)
    }

    override fun close() {
        if (released) return
        released = true
        progressJob?.cancel()
        val finalPosition = currentPositionMs.millisecondsToEmbyTicks()
        val paused = !player.isPlaying
        val canSeek = isSeekSupported
        val playMethod = currentCandidate().method
        exoPlayer.release()
        if (started) {
            scope.launch(Dispatchers.IO) {
                runCatching { report(PlaybackEvent.STOPPED, finalPosition, paused, canSeek, playMethod) }
                scope.cancel()
            }
        } else {
            scope.cancel()
        }
    }

    private fun mediaSource(candidate: PlaybackCandidate) =
        DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(playbackClient)
                .setUserAgent("Shadow Media")
                .setDefaultRequestProperties(candidate.requiredHeaders)
        ).createMediaSource(MediaItem.fromUri(candidate.url))

    private fun prepareCandidate(
        candidate: PlaybackCandidate,
        absolutePositionMs: Long,
        playWhenReady: Boolean,
    ) {
        if (candidate.method == PlayMethod.TRANSCODE) {
            positionOffsetMs = absolutePositionMs
            val url = candidate.url.toHttpUrl().newBuilder()
                .setQueryParameter("StartTimeTicks", absolutePositionMs.millisecondsToEmbyTicks().toString())
                .build()
                .toString()
            exoPlayer.setMediaSource(mediaSource(candidate.copy(url = url)))
        } else {
            positionOffsetMs = 0L
            exoPlayer.setMediaSource(mediaSource(candidate))
            exoPlayer.seekTo(absolutePositionMs)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                reportCurrent(PlaybackEvent.TIME_UPDATE)
            }
        }
    }

    private fun reportCurrent(event: PlaybackEvent) {
        val position = currentPositionMs.millisecondsToEmbyTicks()
        val paused = !player.isPlaying
        val canSeek = isSeekSupported
        val playMethod = currentCandidate().method
        scope.launch(Dispatchers.IO) {
            runCatching { report(event, position, paused, canSeek, playMethod) }
        }
    }

    private suspend fun report(
        event: PlaybackEvent,
        positionTicks: Long,
        paused: Boolean,
        canSeek: Boolean,
        playMethod: PlayMethod,
    ) {
        playbackOutbox.submit(
            session,
            PlaybackReport(
                itemId = plan.itemId,
                mediaSourceId = plan.mediaSourceId,
                playSessionId = plan.playSessionId,
                positionTicks = positionTicks,
                isPaused = paused,
                canSeek = canSeek,
                event = event,
                playMethod = playMethod,
            ),
        )
    }

    private fun tryFallback(error: PlaybackException): Boolean {
        val next = candidateIndex + 1
        if (next >= plan.candidates.size || released) {
            val message = error.message ?: error.errorCodeName
            diagnosticsState.value = diagnostics(message)
            if (!released) onTerminalError(currentPositionMs, message)
            return false
        }
        val absolutePosition = currentPositionMs
        candidateIndex = next
        diagnosticsState.value = diagnostics("上一播放地址失败，已切换回退链路：${error.errorCodeName}")
        prepareCandidate(currentCandidate(), absolutePosition, playWhenReady = true)
        return true
    }

    private fun currentCandidate(): PlaybackCandidate = plan.candidates[candidateIndex]

    private fun transcodingCandidateIndex(): Int =
        plan.candidates.indexOfFirst { it.method == PlayMethod.TRANSCODE }

    private fun diagnostics(error: String? = null): PlaybackDiagnostics {
        val candidate = currentCandidate()
        return PlaybackDiagnostics(
            method = candidate.method,
            candidateIndex = candidateIndex,
            candidateCount = plan.candidates.size,
            requestHost = candidate.url.toHttpUrl().host,
            container = plan.container,
            videoType = plan.videoType,
            videoCodec = plan.videoCodec,
            audioCodec = plan.audioCodec,
            sourceCount = plan.sourceCount,
            supportsDirectPlay = plan.supportsDirectPlay,
            supportsDirectStream = plan.supportsDirectStream,
            supportsTranscoding = plan.supportsTranscoding,
            lastError = error,
        )
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && !started) {
                started = true
                reportCurrent(PlaybackEvent.STARTED)
                startProgressLoop()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!started || released) return
            reportCurrent(if (playWhenReady) PlaybackEvent.UNPAUSE else PlaybackEvent.PAUSE)
        }

        override fun onPlayerError(error: PlaybackException) {
            tryFallback(error)
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
