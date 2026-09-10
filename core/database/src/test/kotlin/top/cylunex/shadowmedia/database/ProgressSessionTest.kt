package top.cylunex.shadowmedia.database

import org.junit.Assert.*
import org.junit.Test

class ProgressSessionTest {
    private val current = ProgressSessionEntity("scoped-asset", "edition-1", "session-new", 8)

    @Test fun acceptsNewerEventsEvenWhenUserMovesBackwards() {
        assertTrue(current.accepts(current.copy(sequence = 9), "edition-1"))
    }
    @Test fun rejectsDuplicateAndOutOfOrderEvents() {
        assertFalse(current.accepts(current, "edition-1"))
        assertFalse(current.accepts(current.copy(sequence = 7), "edition-1"))
    }
    @Test fun replacingAResourceRejectsQueuedOldLocations() {
        assertFalse(current.accepts(current.copy(sequence = 9), "edition-2"))
    }
    @Test fun aLateOldSessionCannotTakeBackOwnership() {
        assertFalse(current.accepts(current.copy(sessionId = "session-old", sequence = 999), "edition-1"))
    }
    @Test fun matchingRemoteIdsFromDifferentScopesCannotWriteEachOther() {
        assertFalse(current.accepts(current.copy(assetId = "other-account-asset", sequence = 9), "edition-1"))
    }
    @Test fun bothCurrentAndIncomingRevisionMustMatchTheAsset() {
        assertFalse(current.accepts(current.copy(resourceRevision = "edition-2", sequence = 9), "edition-1"))
    }
}
