package top.cylunex.shadowmedia.audio

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.supervisorScope
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
    @Test fun `concurrent opens of one entry share one resolution`() = runBlocking {
        val original = LibraryResources.audioResolver
        try {
            val release = CompletableDeferred<Unit>()
            var resolves = 0
            LibraryResources.audioResolver = { _, _ ->
                resolves++; release.await()
                listOf(PlaybackCandidate("https://example.com/$resolves", PlayMethod.DIRECT_STREAM, emptyMap()))
            }
            val sessions = AudioCandidateSessions()
            val first = async(start = CoroutineStart.UNDISPATCHED) { sessions.current(asset, "entry") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { sessions.current(asset, "entry") }
            assertEquals(1, resolves)
            release.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, resolves)
        } finally { LibraryResources.audioResolver = original }
    }
    @Test fun `closing sessions rejects pending and queued resolutions`() = runBlocking {
        val original = LibraryResources.audioResolver
        try { supervisorScope {
            val release = CompletableDeferred<Unit>()
            var resolves = 0
            LibraryResources.audioResolver = { _, _ ->
                resolves++; release.await()
                listOf(PlaybackCandidate("https://example.com/one", PlayMethod.DIRECT_STREAM, emptyMap()))
            }
            val sessions = AudioCandidateSessions()
            val first = async(start = CoroutineStart.UNDISPATCHED) { sessions.current(asset, "entry") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { sessions.current(asset, "entry") }
            sessions.close(); release.complete(Unit)
            assertTrue(runCatching { first.await() }.exceptionOrNull() is java.io.IOException)
            assertTrue(runCatching { second.await() }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, resolves)
            assertFalse(sessions.advance("entry"))
        } } finally { LibraryResources.audioResolver = original }
    }
    @Test fun `refresh preserves fallback advancement while resolving`() = runBlocking {
        val original = LibraryResources.audioResolver
        try {
            val release = CompletableDeferred<Unit>()
            var resolves = 0
            LibraryResources.audioResolver = { _, _ ->
                if (++resolves == 2) release.await()
                listOf("one", "two").map { PlaybackCandidate("https://example.com/$resolves/$it", PlayMethod.DIRECT_STREAM, emptyMap()) }
            }
            val sessions = AudioCandidateSessions()
            sessions.current(asset, "entry")
            val refresh = async(start = CoroutineStart.UNDISPATCHED) { sessions.refresh(asset, "entry") }
            assertTrue(sessions.advance("entry"))
            release.complete(Unit)
            assertTrue(refresh.await())
            assertTrue(sessions.current(asset, "entry").url.endsWith("2/two"))
            assertFalse(sessions.refresh(asset, "entry"))
        } finally { LibraryResources.audioResolver = original }
    }
    @Test fun `closed or superseded candidates never report selection`() = runBlocking {
        val original = LibraryResources.audioResolver
        val originalSelected = LibraryResources.audioCandidateSelected
        try {
            val first = PlaybackCandidate("https://example.com/one", PlayMethod.DIRECT_STREAM, emptyMap())
            val second = first.copy(url = "https://example.com/two")
            LibraryResources.audioResolver = { _, _ -> listOf(first, second) }
            var selected = 0
            LibraryResources.audioCandidateSelected = { _, _ -> selected++ }
            val sessions = AudioCandidateSessions()
            sessions.current(asset, "entry")
            sessions.advance("entry")
            sessions.selected("entry", first)
            assertEquals(0, selected)
            sessions.selected("entry", second)
            assertEquals(1, selected)
            sessions.close()
            sessions.selected("entry", second)
            assertEquals(1, selected)
        } finally {
            LibraryResources.audioResolver = original
            LibraryResources.audioCandidateSelected = originalSelected
        }
    }
}
