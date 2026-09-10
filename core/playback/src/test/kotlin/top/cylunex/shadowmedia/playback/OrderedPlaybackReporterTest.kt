package top.cylunex.shadowmedia.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.network.PlaybackReport

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedPlaybackReporterTest {
    private fun report(event: PlaybackEvent) = PlaybackReport("item", "source", "session", 100, false, true, event, PlayMethod.DIRECT_PLAY)
    @Test fun `slow start cannot be overtaken and close drains through stop`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<PlaybackEvent>()
        val reporter = OrderedPlaybackReporter(this) {
            if (it.event == PlaybackEvent.STARTED) gate.await()
            received += it.event
        }
        reporter.submit(report(PlaybackEvent.STARTED))
        reporter.submit(report(PlaybackEvent.PAUSE))
        reporter.submit(report(PlaybackEvent.STOPPED))
        reporter.close()
        runCurrent()
        assertTrue(received.isEmpty())
        assertFalse(reporter.completion.isCompleted)
        gate.complete(Unit); reporter.completion.join()
        assertEquals(listOf(PlaybackEvent.STARTED, PlaybackEvent.PAUSE, PlaybackEvent.STOPPED), received)
    }
    @Test fun `outbox exception does not prevent subsequent stop`() = runTest {
        val received = mutableListOf<PlaybackEvent>()
        val reporter = OrderedPlaybackReporter(this) {
            if (it.event == PlaybackEvent.TIME_UPDATE) throw java.io.IOException("test disk failure")
            received += it.event
        }
        reporter.submit(report(PlaybackEvent.TIME_UPDATE)); reporter.submit(report(PlaybackEvent.STOPPED)); reporter.close()
        reporter.completion.join()
        assertEquals(listOf(PlaybackEvent.STOPPED), received)
    }
}
