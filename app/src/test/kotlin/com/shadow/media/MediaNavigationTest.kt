package com.shadow.media

import com.shadow.media.library.CatalogEntry
import org.junit.Assert.*
import org.junit.Test

class MediaNavigationTest {
    @Test fun oldOrForeignPreferenceCannotSelectAnotherModulesPage() {
        assertEquals(MediaModule.VIDEO, MediaModule.restore("READING"))
        assertEquals(MediaDestination.MUSIC_LIBRARY, MediaModule.MUSIC.destination("CITY"))
        assertEquals(MediaDestination.BOOK_SOURCES, MediaModule.NOVEL.destination("BOOK_SOURCES"))
        assertEquals(MediaDestination.CHANNELS, MediaModule.LIVE.destination(null))
        assertFalse(MediaModule.LIVE.hasBottomNavigation)
    }

    @Test fun mixedCatalogKeepsFoldersButSeparatesComicsNovelsAndAudio() {
        val folder = CatalogEntry("folder", "混合目录", navigation = "child")
        val novel = CatalogEntry("novel", "小说", format = "epub")
        val comic = CatalogEntry("comic", "漫画", format = "cbz")
        val audio = CatalogEntry("audio", "听书", format = "m4b")
        assertTrue(folder.matchesModule(MediaModule.COMIC))
        assertFalse(novel.matchesModule(MediaModule.COMIC))
        assertTrue(comic.matchesModule(MediaModule.COMIC))
        assertTrue(novel.matchesModule(MediaModule.NOVEL))
        assertFalse(audio.matchesModule(MediaModule.NOVEL))
        assertTrue(audio.matchesModule(MediaModule.AUDIOBOOK))
        assertTrue(novel.matchesModule(null))
        assertTrue(CatalogEntry("song", "歌曲", format = "audio").matchesModule(MediaModule.MUSIC))
    }
}
