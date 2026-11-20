package com.shadow.media.reading

import org.junit.Assert.*
import org.junit.Test

class ComicProgressTest {
    @Test fun `single page and final page complete when visible`() {
        assertTrue(comicSpreadCompleted(0, 1, "LTR"))
        assertTrue(comicSpreadCompleted(9, 10, "RTL"))
        assertFalse(comicSpreadCompleted(8, 10, "LTR"))
    }
    @Test fun `final double spread completes for even and odd page counts`() {
        assertFalse(comicSpreadCompleted(8, 10, "双页"))
        assertTrue(comicSpreadCompleted(9, 10, "双页"))
        assertTrue(comicSpreadCompleted(9, 10, "双页RTL"))
        assertEquals(3, comicSpreadStart(2, "双页RTL"))
        assertTrue(comicSpreadCompleted(10, 11, "双页"))
        assertFalse(comicSpreadCompleted(8, 11, "双页"))
    }
    @Test fun stripCompletesWhenTheLastPageFitsEvenWithEarlierPagesVisible() {
        assertFalse(comicStripCompleted(8, 10, false))
        assertFalse(comicStripCompleted(9, 10, true))
        assertTrue(comicStripCompleted(9, 10, false))
        assertTrue(comicStripCompleted(0, 1, false))
        assertFalse(comicStripCompleted(-1, 0, false))
    }
    @Test fun `empty or invalid page does not complete`() {
        assertFalse(comicSpreadCompleted(0, 0, "LTR"))
        assertFalse(comicSpreadCompleted(-1, 10, "LTR"))
        assertFalse(comicSpreadCompleted(10, 10, "LTR"))
    }
}
