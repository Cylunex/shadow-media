package top.cylunex.shadowmedia.audio

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.LibraryResources
import top.cylunex.shadowmedia.model.PlaybackCandidate

/** Ephemeral URLs belong to one queue instance, and are never persisted or exposed to UI. */
internal class AudioCandidateSessions {
    private data class State(val candidates: List<PlaybackCandidate>, var index: Int = 0, var refreshed: Boolean = false, var selected: PlaybackCandidate? = null)
    @Volatile private var activeEntry: String? = null
    fun priority(entry: String) = if (entry == activeEntry) top.cylunex.shadowmedia.network.ResourcePriority.FOREGROUND else top.cylunex.shadowmedia.network.ResourcePriority.ADJACENT
    fun activate(entry: String?) {
        activeEntry = entry
        AudioDiagnostics.method.value = synchronized(states) { states[entry]?.selected?.method?.name }.orEmpty()
    }
    private val states = linkedMapOf<String, State>()
    private val resolutions = Array(16) { Mutex() }
    private var closed = false
    private fun resolution(entry: String) = resolutions[(entry.hashCode() and Int.MAX_VALUE) % resolutions.size]
    suspend fun current(asset: LibraryAssetEntity, entry: String): PlaybackCandidate = resolution(entry).withLock {
        synchronized(states) {
            if (closed) throw IOException("音频播放会话已关闭")
            states[entry]?.let { return@withLock it.candidates[it.index] }
        }
        val candidates = LibraryResources.resolveAudio(asset, entry, priority(entry)).take(16)
        currentCoroutineContext().ensureActive()
        require(candidates.isNotEmpty()) { "来源没有返回音频线路" }
        synchronized(states) {
            if (closed) throw IOException("音频播放会话已关闭")
            states[entry] = State(candidates)
            while (states.size > 16) states.remove(states.keys.first())
        }
        candidates.first()
    }
    fun advance(entry: String): Boolean = synchronized(states) {
        val state = states[entry] ?: return false
        if (state.index >= state.candidates.lastIndex) return false
        state.index++; true
    }
    suspend fun refresh(asset: LibraryAssetEntity, entry: String): Boolean = resolution(entry).withLock {
        val state = synchronized(states) {
            val state = states[entry] ?: return@withLock false
            if (state.refreshed) return@withLock false
            state.refreshed = true
            state
        }
        val candidates = LibraryResources.resolveAudio(asset, entry, priority(entry)).take(16)
        currentCoroutineContext().ensureActive()
        if (candidates.isEmpty()) return@withLock false
        synchronized(states) {
            if (states[entry] !== state) return@withLock false
            states[entry] = State(candidates, state.index.coerceAtMost(candidates.lastIndex), true)
        }
        true
    }
    fun selected(entry: String, candidate: PlaybackCandidate) = synchronized(states) {
        val state = states[entry] ?: return@synchronized
        if (state.candidates[state.index] != candidate) return@synchronized
        state.selected = candidate
        LibraryResources.audioCandidateSelected?.invoke(entry, candidate)
        if (activeEntry == entry) AudioDiagnostics.method.value = candidate.method.name
    }
    fun close() = synchronized(states) {
        closed = true
        states.clear()
        activeEntry = null
        AudioDiagnostics.method.value = ""
    }
}
