package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class VideoLibraryPolicyTest {
    private fun episode(id: String, series: String?, position: Long = 0, played: Boolean = false) = MediaItem(id, id, "Episode", null, 1, 1, 1000, position, played, false, seriesId = series)
    private val a = VideoQueueEntry("a", MediaKey("local-video", "one"), "one")
    private val b = VideoQueueEntry("b", a.key, "one again")
    private val c = VideoQueueEntry("c", MediaKey("local-video", "two"), "two")
    @Test fun `unfinished episode suppresses the next episode in its series`() {
        val current = episode("one", "show", 100)
        val next = episode("two", "show")
        val other = episode("other", "other-show")
        assertEquals(listOf(current, other), mergeContinueAndNextUp(listOf(current), listOf(next, current, other, other)))
    }
    @Test fun `completed episodes and folders are excluded while next show remains available`() {
        val complete = episode("one", "show", 920)
        val next = episode("two", "show")
        assertEquals(listOf(next), mergeContinueAndNextUp(listOf(complete), listOf(next, complete, episode("folder", null).copy(isFolder = true))))
        assertFalse(videoCompleted(100, null))
        assertFalse(videoCompleted(0, 0))
        assertTrue(videoCompleted(0, null, played = true))
    }
    @Test fun `unknown series does not collapse unrelated episodes`() {
        assertEquals(2, mergeContinueAndNextUp(listOf(episode("one", null, 100)), listOf(episode("two", null))).size)
    }
    @Test fun `duplicate movie occurrences retain distinct editing identity`() {
        val queue = VideoQueue(listOf(a, b, c), b.entryId, 42)
        assertEquals(listOf(b, a, c), queue.move("b", -1).entries)
        assertEquals("b", queue.remove("a").currentEntryId)
        assertEquals(42L, queue.remove("a").positionMs)
        assertEquals("c", queue.remove("b").currentEntryId)
        assertEquals(0L, queue.remove("b").positionMs)
    }
    @Test fun `finite queue ends and explicit repeat wraps`() {
        val queue = VideoQueue(listOf(a, b), b.entryId, 50)
        assertNull(queue.next())
        assertEquals("a", queue.copy(repeat = true).next()?.currentEntryId)
        assertEquals(0L, queue.copy(repeat = true).next()?.positionMs)
        assertEquals(VideoQueue(), VideoQueue(listOf(a), a.entryId).remove("a"))
        assertEquals(queue, queue.select("missing"))
    }
    @Test fun `mixed library detection preserves typed libraries`() {
        assertTrue(MediaLibrary("id", "mixed", null).isMixedVideoLibrary)
        assertTrue(MediaLibrary("id", "mixed", "mixed").isMixedVideoLibrary)
        assertFalse(MediaLibrary("id", "movie", "movies").isMixedVideoLibrary)
    }
}
