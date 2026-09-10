package top.cylunex.shadowmedia

import org.junit.Assert.*
import org.junit.Test

class FeedSessionSnapshotTest {
    private val original = FeedSessionSnapshot("library", listOf("a", "b", "c", "d"), "c", 2, 0)
    @Test fun `deleting earlier item retains current media identity`() {
        val result = original.removing("a", 1)
        assertEquals("c", result.currentItemId)
        assertEquals(1, result.currentIndex)
    }
    @Test fun `deleting current item picks its successor`() {
        val result = original.removing("c", 1)
        assertEquals("d", result.currentItemId)
        assertEquals(2, result.currentIndex)
    }
    @Test fun `deleting final item leaves a valid empty feed`() {
        val result = original.copy(orderedItemIds = listOf("c"), currentIndex = 0).removing("c", 1)
        assertTrue(result.orderedItemIds.isEmpty())
        assertNull(result.currentItemId)
        assertEquals(0, result.currentIndex)
    }
}
