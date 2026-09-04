package top.cylunex.shadowmedia

import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.*

class PlaybackOwnershipTest {
    private val alice = EmbySession("https://example.com", "server", "alice", "Alice", "test-only", false)
    private val bob = alice.copy(userId = "bob", userName = "Bob")
    private fun plan(session: String) = PlaybackPlan("same-item", "source", session,
        listOf(PlaybackCandidate("https://example.com/video", PlayMethod.DIRECT_STREAM, emptyMap())), null, null, null, null, null)
    @Test fun `late stop remains owned by account that opened external player`() {
        val ownership = PlaybackOwnership(); val old = plan("old"); val current = plan("current")
        ownership.remember(old, alice); ownership.remember(current, bob)
        assertEquals(alice, ownership.session(old, PlaybackEvent.STOPPED))
        assertEquals(bob, ownership.session(current, PlaybackEvent.STARTED))
        assertNull(ownership.session(old, PlaybackEvent.STOPPED))
    }
    @Test fun `unknown callback is ignored instead of attributed to active account`() {
        assertNull(PlaybackOwnership().session(plan("unknown"), PlaybackEvent.STARTED))
    }
    @Test fun `retained plans are bounded`() {
        val ownership = PlaybackOwnership()
        repeat(20) { ownership.remember(plan(it.toString()), alice) }
        assertNull(ownership.session(plan("0"), PlaybackEvent.STARTED))
        assertEquals(alice, ownership.session(plan("19"), PlaybackEvent.STARTED))
    }
}
