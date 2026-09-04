package top.cylunex.shadowmedia

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.library.AudioProgressSnapshot
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.network.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmbyAudioReporterTest {
    private class Outbox : PlaybackOutbox {
        override val pendingCount = MutableStateFlow(0)
        val records = mutableListOf<Pair<EmbySession, PlaybackReport>>()
        override suspend fun submit(session: EmbySession, report: PlaybackReport) { records += session to report }
        override suspend fun flush(session: EmbySession) {}
        override suspend fun discard(session: EmbySession) {}
    }
    private val account = EmbySession("https://example.com", "server", "alice", "Alice", "test-only", false)
    private fun plan(id: String) = PlaybackPlan("book", "source", id, listOf(PlaybackCandidate("https://example.com/emby/Audio/book/stream", PlayMethod.DIRECT_STREAM, emptyMap())), "m4b", null, null, "aac", null)
    @Test fun `progress stays with resolved account and balanced session events`() = runTest {
        val outbox = Outbox(); val reporter = EmbyAudioReporter(backgroundScope, outbox)
        reporter.resolved("asset", account, plan("p1"))
        reporter.progress(AudioProgressSnapshot("asset", 0, true, true, false, false))
        runCurrent(); assertTrue(outbox.records.isEmpty())
        reporter.progress(AudioProgressSnapshot("asset", 3000, false, true, true, false))
        reporter.progress(AudioProgressSnapshot("asset", 5000, true, true, false, true))
        runCurrent()
        assertEquals(listOf(PlaybackEvent.STARTED, PlaybackEvent.TIME_UPDATE, PlaybackEvent.STOPPED), outbox.records.map { it.second.event })
        assertTrue(outbox.records.all { it.first == account && it.second.playSessionId == "p1" })
        assertEquals(50_000_000L, outbox.records.last().second.positionTicks)
        reporter.progress(AudioProgressSnapshot("asset", 5000, true, true, false, true)); runCurrent()
        assertEquals(3, outbox.records.size)
    }
    @Test fun `refresh ends old session before opening replacement`() = runTest {
        val outbox = Outbox(); val reporter = EmbyAudioReporter(backgroundScope, outbox)
        reporter.resolved("asset", account, plan("old"))
        reporter.progress(AudioProgressSnapshot("asset", 1000, false, true, true, false))
        reporter.resolved("asset", account, plan("new"))
        reporter.progress(AudioProgressSnapshot("asset", 2000, false, true, true, false))
        runCurrent()
        assertEquals(PlaybackEvent.STOPPED, outbox.records[2].second.event)
        assertEquals("old", outbox.records[2].second.playSessionId)
        assertEquals("new", outbox.records[3].second.playSessionId)
    }
}
