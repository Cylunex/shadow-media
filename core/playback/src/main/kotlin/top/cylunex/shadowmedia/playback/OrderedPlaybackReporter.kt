package top.cylunex.shadowmedia.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.network.PlaybackReport

/** Preserve callback order while a slow server/outbox is still processing an earlier event. */
internal class OrderedPlaybackReporter(scope: CoroutineScope, send: suspend (PlaybackReport) -> Unit) {
    private val events = Channel<PlaybackReport>(Channel.UNLIMITED)
    val completion = scope.launch {
        for (event in events) {
            try { send(event) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Transport retries are retained by the persistent outbox. */ }
        }
    }
    fun submit(report: PlaybackReport) { check(events.trySend(report).isSuccess) }
    fun close() { events.close() }
}
