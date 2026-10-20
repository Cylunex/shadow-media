package top.cylunex.shadowmedia.database

import org.junit.Assert.*
import org.junit.Test

class NovelReadingStateTest {
    @Test fun acknowledgementCannotClearReadingThatArrivedDuringFlight() {
        val state = NovelReadingStateEntity("id", "scope", "work", "client", revision = 3,
            localSequence = 8, acknowledgedSequence = 5, localPosition = "latest-offline-position",
            nextClientSeq = 4, requestJson = "frozen-request-for-sequence-6", requestSequence = 6)
        val acknowledged = state.acknowledged(4)
        assertTrue(acknowledged.pending); assertEquals(6L, acknowledged.acknowledgedSequence)
        assertEquals("latest-offline-position", acknowledged.localPosition); assertEquals(4L, acknowledged.nextClientSeq)
        assertEquals("", acknowledged.requestJson); assertEquals(4L, acknowledged.revision)
    }
    @Test fun aFullyAcknowledgedSnapshotIsClean() {
        val state = NovelReadingStateEntity("id", "scope", "work", "client", localSequence = 6, requestSequence = 6, requestJson = "request")
        assertFalse(state.acknowledged(7).pending)
    }
}
