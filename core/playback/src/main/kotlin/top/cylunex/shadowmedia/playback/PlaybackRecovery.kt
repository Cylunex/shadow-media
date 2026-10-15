package top.cylunex.shadowmedia.playback

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaybackRecoveryPhase { NONE, WAITING_FOR_NETWORK, WAITING_FOR_PLAY, RETRYING, FAILED }

data class PlaybackRecoveryState(
    val phase: PlaybackRecoveryPhase = PlaybackRecoveryPhase.NONE,
    val failure: PlaybackFailure? = null,
    val attempt: Int = 0,
    val attemptLimit: Int = 2,
) {
    val message: String? get() = when (phase) {
        PlaybackRecoveryPhase.NONE -> null
        PlaybackRecoveryPhase.WAITING_FOR_NETWORK -> "网络已断开，连接恢复后继续播放"
        PlaybackRecoveryPhase.WAITING_FOR_PLAY -> "已暂停恢复，继续播放后重试"
        PlaybackRecoveryPhase.RETRYING -> "正在恢复播放（$attempt/$attemptLimit）…"
        PlaybackRecoveryPhase.FAILED -> failure?.message
    }
}

/** Main-thread confined. Budget survives READY and candidate changes to prevent retry loops. */
class PlaybackRecovery(
    private val scope: CoroutineScope,
    private val networkAllowed: () -> Boolean,
    private val maxAttempts: () -> Int = { 2 },
    private val retry: () -> Unit,
) : Closeable {
    private val mutableState = MutableStateFlow(PlaybackRecoveryState())
    val state: StateFlow<PlaybackRecoveryState> = mutableState.asStateFlow()
    private var pending: PlaybackFailure? = null
    private var job: Job? = null
    private var attempts = 0
    private var networkAvailable = true // Unknown/unsupported monitor must not block playback.
    private var playWhenReady = true
    private var foreground = true
    private var closed = false

    fun recover(failure: PlaybackFailure): Boolean {
        if (closed) return false
        cancelPending()
        if (!networkAllowed()) {
            fail(offlineFailure())
            return true
        }
        if (!failure.canAutoRetry || attempts >= maxAttempts().coerceIn(0, 5)) {
            fail(failure)
            return false
        }
        pending = failure
        schedule()
        return true
    }

    fun setNetworkAvailable(available: Boolean) {
        if (networkAvailable == available || closed) return
        networkAvailable = available
        schedule()
    }

    fun setPlayWhenReady(playing: Boolean) {
        if (playWhenReady == playing || closed) return
        playWhenReady = playing
        schedule()
    }

    fun setForeground(active: Boolean) {
        if (foreground == active || closed) return
        foreground = active
        schedule()
    }

    fun ready() {
        if (closed) return
        cancelPending()
        mutableState.value = PlaybackRecoveryState(attempt = attempts)
    }

    fun manualRetry(): Boolean {
        if (closed) return false
        cancelPending()
        if (!networkAllowed()) {
            fail(offlineFailure())
            return false
        }
        attempts = 0
        mutableState.value = PlaybackRecoveryState()
        return true
    }

    private fun schedule() {
        job?.cancel()
        job = null
        val failure = pending ?: return
        if (closed) return
        if (attempts >= maxAttempts().coerceIn(0, 5)) { fail(failure); return }
        if (!networkAllowed()) { fail(offlineFailure()); return }
        val phase = when {
            !foreground || !playWhenReady -> PlaybackRecoveryPhase.WAITING_FOR_PLAY
            !networkAvailable -> PlaybackRecoveryPhase.WAITING_FOR_NETWORK
            else -> PlaybackRecoveryPhase.RETRYING
        }
        mutableState.value = PlaybackRecoveryState(phase, failure, attempts + 1, maxAttempts())
        if (phase != PlaybackRecoveryPhase.RETRYING) return
        job = scope.launch {
            delay(if (attempts == 0) 1_000L else 3_000L)
            job = null
            if (attempts >= maxAttempts().coerceIn(0, 5)) { fail(failure); return@launch }
            if (!networkAllowed()) { fail(offlineFailure()); return@launch }
            pending = null
            attempts++
            retry()
        }
    }

    private fun fail(failure: PlaybackFailure) {
        cancelPending()
        mutableState.value = PlaybackRecoveryState(PlaybackRecoveryPhase.FAILED, failure, attempts, maxAttempts())
    }

    private fun cancelPending() { job?.cancel(); job = null; pending = null }
    override fun close() { closed = true; cancelPending() }
    private fun offlineFailure() = PlaybackFailure(PlaybackFailureKind.OFFLINE, "仅离线模式已开启，请关闭后重试网络视频")
}
