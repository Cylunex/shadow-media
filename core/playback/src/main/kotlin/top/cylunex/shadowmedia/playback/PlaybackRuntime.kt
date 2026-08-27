package top.cylunex.shadowmedia.playback

import android.content.Context
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
import top.cylunex.shadowmedia.model.millisecondsToEmbyTicks
import top.cylunex.shadowmedia.network.ClientIdentity
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.network.PlaybackReport

data class PlaybackDiagnostics(
    val method: PlayMethod,
    val candidateIndex: Int,
    val candidateCount: Int,
    val requestHost: String,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val lastError: String? = null,
)

class PlaybackRuntime(
    context: Context,
    private val session: EmbySession,
    private val plan: PlaybackPlan,
    private val repository: EmbyRepository,
    clientIdentity: ClientIdentity,
    startPositionMs: Long,
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
    private var started = false
    private var progressJob: Job? = null
    private var released = false
    private val diagnosticsState = MutableStateFlow(diagnostics())

    val diagnostics: StateFlow<PlaybackDiagnostics> = diagnosticsState.asStateFlow()
    private val exoPlayer = ExoPlayer.Builder(context).build()
    val player: Player = exoPlayer

    init {
        exoPlayer.addListener(PlayerListener())
        exoPlayer.setMediaSource(mediaSource(plan.primary))
        exoPlayer.seekTo(startPositionMs.coerceAtLeast(0))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun close() {
        if (released) return
        released = true
        progressJob?.cancel()
        val finalPosition = player.currentPosition.millisecondsToEmbyTicks()
        val paused = !player.isPlaying
        exoPlayer.release()
        if (started) {
            scope.launch(Dispatchers.IO) {
                runCatching { report(PlaybackEvent.STOPPED, finalPosition, paused) }
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
        val position = player.currentPosition.millisecondsToEmbyTicks()
        val paused = !player.isPlaying
        scope.launch(Dispatchers.IO) { runCatching { report(event, position, paused) } }
    }

    private suspend fun report(event: PlaybackEvent, positionTicks: Long, paused: Boolean) {
        repository.reportPlayback(
            session,
            PlaybackReport(
                plan = plan,
                positionTicks = positionTicks,
                isPaused = paused,
                event = event,
                playMethod = currentCandidate().method,
            ),
        )
    }

    private fun tryFallback(error: PlaybackException): Boolean {
        val next = candidateIndex + 1
        if (next >= plan.candidates.size || released) {
            diagnosticsState.value = diagnostics(error.message ?: error.errorCodeName)
            return false
        }
        candidateIndex = next
        diagnosticsState.value = diagnostics("上一播放地址失败，已切换回退链路：${error.errorCodeName}")
        exoPlayer.setMediaSource(mediaSource(currentCandidate()), player.currentPosition)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        return true
    }

    private fun currentCandidate(): PlaybackCandidate = plan.candidates[candidateIndex]

    private fun diagnostics(error: String? = null): PlaybackDiagnostics {
        val candidate = currentCandidate()
        return PlaybackDiagnostics(
            method = candidate.method,
            candidateIndex = candidateIndex,
            candidateCount = plan.candidates.size,
            requestHost = candidate.url.toHttpUrl().host,
            container = plan.container,
            videoCodec = plan.videoCodec,
            audioCodec = plan.audioCodec,
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
