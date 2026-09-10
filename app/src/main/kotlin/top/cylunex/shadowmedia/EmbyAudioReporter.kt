package top.cylunex.shadowmedia

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import top.cylunex.shadowmedia.library.AudioProgressSnapshot
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.network.*

/** Ephemeral playback sessions belong to the exact account/plan that produced the audio URL. */
internal class EmbyAudioReporter(scope: CoroutineScope, private val outbox: PlaybackOutbox) {
    private sealed interface Event {
        data class Resolved(val entryId: String, val session: EmbySession, val plan: PlaybackPlan) : Event
        data class Progress(val snapshot: AudioProgressSnapshot) : Event
    }
    private data class Binding(val session: EmbySession, val plan: PlaybackPlan, var started: Boolean = false, var position: Long = 0, var stopped: Boolean = false)
    private val queue = Channel<Event>(Channel.UNLIMITED)
    private val bindings = linkedMapOf<String, Binding>()
    init { scope.launch {
        for (event in queue) {
            try {
                when (event) {
                    is Event.Resolved -> {
                        bindings.remove(event.entryId)?.let { old -> if (old.started) report(old, old.position, true, true, PlaybackEvent.STOPPED) }
                        bindings[event.entryId] = Binding(event.session, event.plan)
                        // Only currently opened loader resources have sessions; no whole-book prefetch.
                        if (bindings.size > 16) bindings.entries.firstOrNull { !it.value.started }?.key?.let(bindings::remove)
                    }
                    is Event.Progress -> {
                        val s = event.snapshot; val binding = bindings[s.entryId] ?: continue
                        if (!s.ready && !s.stopped) continue
                        // Repeating buffered audio need not resolve a new URL. Repeated terminal
                        // callbacks must not reopen the session, but a new playable snapshot can.
                        if (binding.stopped && s.stopped) continue
                        binding.position = s.positionMs
                        if (!binding.started && s.ready) {
                            report(binding, s.positionMs, s.paused, s.canSeek, PlaybackEvent.STARTED)
                            binding.started = true; binding.stopped = false
                        }
                        if (binding.started) report(binding, s.positionMs, s.paused, s.canSeek, if (s.stopped) PlaybackEvent.STOPPED else PlaybackEvent.TIME_UPDATE)
                        if (s.stopped) { binding.started = false; binding.stopped = true }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Room outbox preserves network failures; malformed legacy data remains intact. */ }
        }
    } }
    fun resolved(entryId: String, session: EmbySession, plan: PlaybackPlan) { queue.trySend(Event.Resolved(entryId, session, plan)) }
    fun progress(snapshot: AudioProgressSnapshot) { queue.trySend(Event.Progress(snapshot)) }
    private suspend fun report(binding: Binding, position: Long, paused: Boolean, canSeek: Boolean, event: PlaybackEvent) {
        val plan = binding.plan
        outbox.submit(binding.session, PlaybackReport(plan.itemId, plan.mediaSourceId, plan.playSessionId,
            position.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE / 10_000) * 10_000, paused, canSeek, event, plan.primary.method))
    }
}
