package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class ContentModelsTest {
    @Test fun `unknown types never become video`() {
        assertEquals(ContentKind.UNKNOWN, contentKind("unexpected"))
        assertEquals(ContentKind.AUDIOBOOK, contentKindForFile("Book.M4B"))
        assertEquals(ContentKind.BOOK, contentKindForFile("小说.epub"))
    }
    @Test fun `scoped ids cannot collide using delimiters`() {
        assertNotEquals(scopedContentId("a:b", "c"), scopedContentId("a", "b:c"))
        assertNotEquals(scopedContentId("用户一", "书"), scopedContentId("用户二", "书"))
    }
    @Test(expected = IllegalArgumentException::class) fun `page offset rejects NaN`() {
        ProgressLocator.Page("a", 0, Float.NaN)
    }
    @Test fun `backward seek remains valid`() {
        val later = ProgressLocator.Time("track", 900)
        val earlier = later.copy(positionMs = 100)
        assertEquals(100L, earlier.positionMs)
    }
}
