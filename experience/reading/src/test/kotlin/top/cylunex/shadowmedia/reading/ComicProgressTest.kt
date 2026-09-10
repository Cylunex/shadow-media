package top.cylunex.shadowmedia.reading

import org.junit.Assert.*
import org.junit.Test

class ComicProgressTest {
    @Test fun `single page and final page complete when visible`() {
        assertTrue(comicCompleted(0, 1, "LTR", 0f))
        assertTrue(comicCompleted(9, 10, "RTL", 0f))
        assertFalse(comicCompleted(8, 10, "LTR", 1f))
    }
    @Test fun `final double spread completes for even and odd page counts`() {
        assertTrue(comicCompleted(8, 10, "双页", 0f))
        assertTrue(comicCompleted(10, 11, "双页", 0f))
        assertFalse(comicCompleted(8, 11, "双页", 0f))
    }
    @Test fun `long image requires reaching bottom of last page`() {
        assertFalse(comicCompleted(9, 10, "长图", 0f))
        assertTrue(comicCompleted(9, 10, "长图", 1f))
        assertFalse(comicCompleted(9, 10, "长图", Float.NaN))
    }
    @Test fun `empty or invalid page does not complete`() {
        assertFalse(comicCompleted(0, 0, "LTR", 1f))
        assertFalse(comicCompleted(-1, 10, "LTR", 1f))
        assertFalse(comicCompleted(10, 10, "LTR", 1f))
    }
}
