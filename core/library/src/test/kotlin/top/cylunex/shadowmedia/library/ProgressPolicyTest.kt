package top.cylunex.shadowmedia.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.database.SyncOperationEntity

class ProgressPolicyTest {
    @Test fun `track transition retains known duration without mutating incoming snapshot`() {
        val incoming = JSONObject().put("positionMs", 40_000).put("durationMs", JSONObject.NULL)
        val result = ProgressPolicy.timeSnapshot(incoming, JSONObject().put("durationMs", 90_000))
        assertEquals(90_000L, result.getLong("durationMs"))
        assertEquals(40_000L, result.getLong("positionMs"))
        assertTrue(incoming.isNull("durationMs"))
    }
    @Test fun `fresh duration wins and a rewind is not merged as maximum progress`() {
        val result = ProgressPolicy.timeSnapshot(JSONObject().put("positionMs", 2_000).put("durationMs", 50_000),
            JSONObject().put("positionMs", 40_000).put("durationMs", 90_000))
        assertEquals(2_000L, result.getLong("positionMs"))
        assertEquals(50_000L, result.getLong("durationMs"))
    }
    @Test fun `negative positions are normalized`() {
        assertEquals(0L, ProgressPolicy.timeSnapshot(JSONObject().put("positionMs", -1), null).getLong("positionMs"))
    }
    @Test fun `ABS position is global and bounded by track and book`() {
        assertEquals(103.0, ProgressPolicy.absPosition(100.0, 20.0, 130.0, 3000), 0.0)
        assertEquals(120.0, ProgressPolicy.absPosition(100.0, 20.0, 130.0, 50_000), 0.0)
        assertEquals(110.0, ProgressPolicy.absPosition(100.0, 20.0, 110.0, 50_000), 0.0)
        assertEquals(100.0, ProgressPolicy.absPosition(100.0, 20.0, 130.0, -1), 0.0)
        assertEquals("book", ProgressPolicy.absBookId("book::track"))
    }
    @Test fun `invalid server durations never generate NaN progress`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ProgressPolicy.absPosition(0.0, 10.0, invalid, 1000) }
        }
    }
    private fun operation(id: String, target: String, time: Long, next: Long = 0, kind: String = "progress") =
        SyncOperationEntity(id, "account", target, kind, "{}", time, nextAttemptAt = next)
    @Test fun `latest book position supersedes old per-track retry including intentional rewind`() {
        val old = operation("old", "track-one", 1)
        val new = operation("new", "abs-book:book", 2, next = Long.MAX_VALUE)
        val unrelated = operation("other", "abs-book:other", 1)
        assertEquals(setOf("new", "other"), ProgressPolicy.latestOperations(listOf(old, new, unrelated), mapOf("old" to "abs-book:book")))
    }
    @Test fun `different operation kinds are never coalesced together`() {
        assertEquals(setOf("a", "b"), ProgressPolicy.latestOperations(listOf(operation("a", "book", 1), operation("b", "book", 2, kind = "annotation")), emptyMap()))
    }
}
