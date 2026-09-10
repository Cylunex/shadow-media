package top.cylunex.shadowmedia.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.LibraryResources
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackCandidate

class AudioCandidateSessionsTest {
    private val asset = LibraryAssetEntity("asset", "provider", "item", "song", kind = "MUSIC", format = "mp3", addedAt = 0)
    @Test fun `fallback is bounded and independent for duplicate queue entries`() = runBlocking {
        val original = LibraryResources.audioResolver
        try {
            LibraryResources.audioResolver = { _, _ -> listOf(PlaybackCandidate("https://example.com/one", PlayMethod.DIRECT_STREAM, emptyMap()), PlaybackCandidate("https://example.com/two", PlayMethod.TRANSCODE, emptyMap())) }
            val sessions = AudioCandidateSessions()
            assertTrue(sessions.current(asset, "first").url.endsWith("one"))
            assertTrue(sessions.advance("first"))
            assertTrue(sessions.current(asset, "first").url.endsWith("two"))
            assertFalse(sessions.advance("first"))
            assertTrue(sessions.current(asset, "second").url.endsWith("one"))
        } finally { LibraryResources.audioResolver = original }
    }
    @Test fun `expired lease only re-resolves once per queue instance`() = runBlocking {
        val original = LibraryResources.audioResolver
        try {
            var resolves = 0
            LibraryResources.audioResolver = { _, _ -> resolves++; listOf(PlaybackCandidate("https://example.com/$resolves", PlayMethod.DIRECT_STREAM, emptyMap())) }
            val sessions = AudioCandidateSessions()
            sessions.current(asset, "entry")
            assertTrue(sessions.refresh(asset, "entry"))
            assertFalse(sessions.refresh(asset, "entry"))
            assertEquals(2, resolves)
            assertTrue(sessions.current(asset, "entry").url.endsWith("2"))
        } finally { LibraryResources.audioResolver = original }
    }
}
