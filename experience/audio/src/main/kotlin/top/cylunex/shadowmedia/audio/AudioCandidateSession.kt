package top.cylunex.shadowmedia.audio

import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.LibraryResources
import top.cylunex.shadowmedia.model.PlaybackCandidate

/** Ephemeral URLs belong to one queue instance, and are never persisted or exposed to UI. */
internal class AudioCandidateSessions {
    private data class State(val candidates: List<PlaybackCandidate>, var index: Int = 0, var refreshed: Boolean = false)
    private val states = linkedMapOf<String, State>()
    suspend fun current(asset: LibraryAssetEntity, entry: String): PlaybackCandidate {
        synchronized(states) { states[entry]?.let { return it.candidates[it.index] } }
        val candidates = LibraryResources.resolveAudio(asset, entry).take(16)
        require(candidates.isNotEmpty()) { "来源没有返回音频线路" }
        synchronized(states) {
            states[entry] = State(candidates)
            while (states.size > 16) states.remove(states.keys.first())
        }
        return candidates.first()
    }
    fun advance(entry: String): Boolean = synchronized(states) {
        val state = states[entry] ?: return false
        if (state.index >= state.candidates.lastIndex) return false
        state.index++; true
    }
    suspend fun refresh(asset: LibraryAssetEntity, entry: String): Boolean {
        val index = synchronized(states) {
            val state = states[entry] ?: return false
            if (state.refreshed) return false
            state.refreshed = true
            state.index
        }
        val candidates = LibraryResources.resolveAudio(asset, entry).take(16)
        if (candidates.isEmpty()) return false
        synchronized(states) { states[entry] = State(candidates, index.coerceAtMost(candidates.lastIndex), true) }
        return true
    }
    fun selected(entry: String, candidate: PlaybackCandidate) {
        LibraryResources.audioCandidateSelected?.invoke(entry, candidate)
        AudioDiagnostics.method.value = candidate.method.name
    }
    fun clear() = synchronized(states) { states.clear() }
}
