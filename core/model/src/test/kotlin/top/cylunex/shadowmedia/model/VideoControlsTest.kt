package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class VideoControlsTest {
    @Test fun `matches same movie and language variants without matching other titles`() {
        for (name in listOf("Film.srt", "film.ASS", "Film.zh-Hans.vtt", "Film.en.srt")) {
            assertTrue(name, isCompanionSubtitle("Film.mkv", name))
        }
        for (name in listOf("Film2.srt", "Film trailer.srt", "Film.mp4", "Other.ass")) {
            assertFalse(name, isCompanionSubtitle("Film.mkv", name))
        }
        assertTrue(isCompanionSubtitle("A.B.2026.mp4", "A.B.2026.zh.srt"))
        assertFalse(isCompanionSubtitle("A.B.2026.mp4", "A.B.2025.srt"))
    }
    @Test fun `subtitle mime types cover supported parsers`() {
        assertEquals("application/x-subrip", subtitleMimeType("字幕.SRT"))
        assertEquals("text/x-ssa", subtitleMimeType("字幕.ass"))
        assertEquals("text/vtt", subtitleMimeType("字幕.vtt"))
        assertNull(subtitleMimeType("video.mkv"))
    }
    @Test fun `swipe seek remains within media bounds and ignores unknown duration`() {
        assertEquals(0L, gestureSeekPosition(10_000, 90_000, -1f))
        assertEquals(90_000L, gestureSeekPosition(80_000, 90_000, 1f))
        assertEquals(45_000L, gestureSeekPosition(15_000, 90_000, .25f))
        assertEquals(0L, gestureSeekPosition(15_000, -1, .25f))
    }
}
