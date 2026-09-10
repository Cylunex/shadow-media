package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class ContinuousAudioTest {
    @Test fun `explicit music classification leaves old file semantics intact`() {
        assertEquals(ContentKind.AUDIOBOOK, contentKindForFile("story.mp3"))
        assertEquals(ContentKind.MUSIC, contentKind("MUSIC"))
        assertEquals(ContentKind.FOLDER, contentKind("MusicArtist"))
        assertTrue(contentKind("PODCAST").isAudio())
    }
    @Test fun `chapter spanning files maps to physical track without negative seek`() {
        val chapters = listOf(BookChapter("a", "第一章", 0, 120000), BookChapter("b", "第二章", 120000, 200000))
        val mapped = trackChapters(chapters, "track-2", "rev", 100000, 60000)
        assertEquals(listOf(0L, 20000L), mapped.map { it.startMs })
        assertEquals(listOf(20000L, 60000L), mapped.map { it.endMs })
        assertTrue(mapped.all { it.trackId == "track-2" && it.resourceRevision == "rev" })
    }
    @Test fun `chapter exactly at track end belongs to next track`() {
        assertTrue(trackChapters(listOf(BookChapter("a", "a", 1000, 2000)), "t", "r", 0, 1000).isEmpty())
    }
    @Test fun `LRC multiple timestamps and global offset preserve order`() {
        val lyrics = parseLyrics("[ar:Example]\n[offset:-50]\n[00:01.25][00:03.005]你好\n[00:02]世界")
        assertEquals(listOf(1200L, 1950L, 2955L), lyrics.map { it.timeMs })
        assertEquals(listOf("你好", "世界", "你好"), lyrics.map { it.text })
    }
    @Test fun `plain lyrics stay readable without timings`() {
        assertEquals(listOf(LyricLine(null, "第一行"), LyricLine(null, "第二行")), parseLyrics("第一行\n\n第二行"))
    }
    @Test fun `ownership pauses prior audio and stale disposal cannot cancel new owner`() {
        val coordinator = PlaybackCoordinator()
        val paused = mutableListOf<String>()
        val audio = coordinator.participant { paused += "audio" }
        val video = coordinator.participant { paused += "video" }
        val tts = coordinator.participant { paused += "tts" }
        audio.claim(); audio.claim(); video.claim(); audio.close(); tts.claim()
        assertEquals(listOf("audio", "video"), paused)
        tts.close(); video.claim()
        assertEquals(listOf("audio", "video"), paused)
    }
    @Test fun `closed playback participant cannot claim again`() {
        val coordinator = PlaybackCoordinator()
        var paused = false
        val first = coordinator.participant { paused = true }
        val disposed = coordinator.participant { }
        disposed.close(); first.claim(); disposed.claim()
        assertFalse(paused)
    }
}
