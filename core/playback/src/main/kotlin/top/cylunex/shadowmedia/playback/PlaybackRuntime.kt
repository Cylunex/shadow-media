package top.cylunex.shadowmedia.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.UUID
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
    private val mediaTitle: String = "Shadow Media",
    private val telemetrySink: PlaybackTelemetrySink = PlaybackTelemetrySink.NONE,
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
    private val telemetryId = UUID.randomUUID().toString()
    private val telemetryStartedAtEpochMs = System.currentTimeMillis()
    private val telemetryStartedElapsedMs = SystemClock.elapsedRealtime()
    private var firstFrameMs: Long? = null
    private var bufferingCount = 0
    private var bufferingStartedElapsedMs: Long? = null
    private var bufferingDurationMs = 0L
    private var telemetryErrorCode: String? = null
    private var telemetryErrorMessage: String? = null
    private var terminalFailure = false
    private val diagnosticsState = MutableStateFlow(diagnostics())
    private val tracksState = MutableStateFlow(PlaybackTracksState())

    val diagnostics: StateFlow<PlaybackDiagnostics> = diagnosticsState.asStateFlow()
    val tracks: StateFlow<PlaybackTracksState> = tracksState.asStateFlow()
    private val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        repeatMode = Player.REPEAT_MODE_ONE
    }
    val player: Player = exoPlayer
    private val mediaSession = MediaSession.Builder(context, exoPlayer).build()
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
        telemetrySink.record(telemetrySnapshot(completed = false))
        prepareCandidate(plan.primary, startPositionMs.coerceAtLeast(0L), playWhenReady = true)
    }

    fun nextAudioTrack() = cycleTrack(C.TRACK_TYPE_AUDIO)

    fun nextSubtitleTrack() = cycleTrack(C.TRACK_TYPE_TEXT)

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
        finishBuffering()
        telemetrySink.record(telemetrySnapshot(completed = !terminalFailure, terminal = true))
        mediaSession.release()
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
        ).createMediaSource(
            MediaItem.Builder()
                .setUri(candidate.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaTitle).build())
                .build()
        )

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
            telemetryErrorCode = error.errorCodeName
            telemetryErrorMessage = message
            terminalFailure = true
            telemetrySink.record(telemetrySnapshot(completed = false, terminal = true))
            diagnosticsState.value = diagnostics(message)
            if (!released) onTerminalError(currentPositionMs, message)
            return false
        }
        val absolutePosition = currentPositionMs
        candidateIndex = next
        telemetryErrorCode = error.errorCodeName
        telemetryErrorMessage = error.message ?: error.errorCodeName
        telemetrySink.record(telemetrySnapshot(completed = false))
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

    private fun cycleTrack(trackType: Int) {
        if (released) return
        val choices = flattenedTracks(trackType)
        if (trackType == C.TRACK_TYPE_TEXT) {
            val selected = choices.indexOfFirst { it.selected }
            if (selected < 0) {
                choices.firstOrNull()?.let { selectTrack(trackType, it) }
            } else if (selected == choices.lastIndex) {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            } else {
                selectTrack(trackType, choices[selected + 1])
            }
        } else {
            if (choices.size < 2) return
            val selected = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
            selectTrack(trackType, choices[(selected + 1) % choices.size])
        }
    }

    private fun selectTrack(trackType: Int, choice: TrackChoice) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .clearOverridesOfType(trackType)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.trackIndex)))
            .build()
    }

    private fun flattenedTracks(trackType: Int): List<TrackChoice> = exoPlayer.currentTracks.groups
        .filter { it.type == trackType }
        .flatMap { group ->
            (0 until group.length).map { index ->
                val format = group.getTrackFormat(index)
                TrackChoice(
                    group = group,
                    trackIndex = index,
                    selected = group.isTrackSelected(index),
                    label = format.label
                        ?: format.language?.uppercase()
                        ?: format.codecs
                        ?: if (trackType == C.TRACK_TYPE_AUDIO) "音轨 ${index + 1}" else "字幕 ${index + 1}",
                )
            }
        }

    private fun publishTracks() {
        val audio = flattenedTracks(C.TRACK_TYPE_AUDIO)
        val subtitles = flattenedTracks(C.TRACK_TYPE_TEXT)
        tracksState.value = PlaybackTracksState(
            audioTracks = audio.map(TrackChoice::label),
            selectedAudioIndex = audio.indexOfFirst(TrackChoice::selected),
            subtitleTracks = subtitles.map(TrackChoice::label),
            selectedSubtitleIndex = subtitles.indexOfFirst(TrackChoice::selected),
        )
    }

    private fun finishBuffering() {
        bufferingStartedElapsedMs?.let { startedAt ->
            bufferingDurationMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            bufferingStartedElapsedMs = null
        }
    }

    private fun telemetrySnapshot(completed: Boolean, terminal: Boolean = false): PlaybackTelemetrySnapshot {
        val candidate = currentCandidate()
        return PlaybackTelemetrySnapshot(
            id = telemetryId,
            providerId = "emby:${session.serverId}:${session.userId}",
            itemId = plan.itemId,
            playSessionId = plan.playSessionId,
            method = candidate.method.name,
            requestHost = runCatching { candidate.url.toHttpUrl().host }.getOrNull(),
            candidateIndex = candidateIndex,
            startedAtEpochMs = telemetryStartedAtEpochMs,
            firstFrameMs = firstFrameMs,
            bufferingCount = bufferingCount,
            bufferingDurationMs = bufferingDurationMs,
            errorCode = telemetryErrorCode,
            errorMessage = telemetryErrorMessage,
            completed = completed,
            terminal = terminal,
        )
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING && bufferingStartedElapsedMs == null) {
                bufferingCount++
                bufferingStartedElapsedMs = SystemClock.elapsedRealtime()
            } else if (playbackState != Player.STATE_BUFFERING) {
                finishBuffering()
            }
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

        override fun onRenderedFirstFrame() {
            if (firstFrameMs == null) {
                firstFrameMs = (SystemClock.elapsedRealtime() - telemetryStartedElapsedMs).coerceAtLeast(0L)
                telemetrySink.record(telemetrySnapshot(completed = false))
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            publishTracks()
        }
    }

    private data class TrackChoice(
        val group: Tracks.Group,
        val trackIndex: Int,
        val selected: Boolean,
        val label: String,
    )

    companion object {
        private const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
