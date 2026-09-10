package top.cylunex.shadowmedia.audio

import org.junit.Assert.*
import org.junit.Test

class AudioChaptersTest {
    @Test fun `normalizes duplicate metadata and missing titles in timeline order`() {
        val chapters = normalizeChapters(listOf(AudioChapter("后半章", 1000), AudioChapter("", 0), AudioChapter("重复轨道元数据", 0)), 2000)
        assertEquals(listOf(AudioChapter("章节 1", 0, 1000), AudioChapter("后半章", 1000, 2000)), chapters)
    }
    @Test fun `rejects invalid starts and clips overlapping ends to the next chapter`() {
        val chapters = normalizeChapters(listOf(AudioChapter("bad", -1), AudioChapter("one", 0, 9000), AudioChapter("two", 1000, 500), AudioChapter("past end", 2000)), 2000)
        assertEquals(listOf(AudioChapter("one", 0, 1000), AudioChapter("two", 1000, 2000)), chapters)
    }
    @Test fun `chapter selection uses half open intervals and preserves gaps`() {
        val chapters = normalizeChapters(listOf(AudioChapter("one", 0, 500), AudioChapter("two", 1000)), 2000)
        assertEquals("one", chapters.chapterAt(499)?.title)
        assertNull(chapters.chapterAt(500))
        assertEquals("two", chapters.chapterAt(1000)?.title)
        assertNull(chapters.chapterAt(2000))
    }
    @Test fun `unknown file duration leaves last chapter open`() {
        val chapters = normalizeChapters(listOf(AudioChapter("one", 1000, -1)), null)
        assertNull(chapters.single().endMs)
        assertNull(chapters.chapterAt(999))
        assertEquals("one", chapters.chapterAt(Long.MAX_VALUE)?.title)
    }
}
