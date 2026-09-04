package top.cylunex.shadowmedia.provider

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.*

class OpenProviderTest {
    private val candidate = PlaybackCandidate("https://example.com/movie.iso", PlayMethod.DIRECT_PLAY, emptyMap())
    private fun provider(id: String = "p", waitMs: Long = 0) = object : MediaProvider {
        override val descriptor = ProviderDescriptor(id, id, ProviderKind.EMBY, setOf(ProviderCapability.SEARCH))
        override suspend fun home() = emptyList<ProviderSection>()
        override suspend fun browse(request: ProviderBrowseRequest) = UnifiedMediaPage(emptyList())
        override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
            delay(waitMs)
            return UnifiedMediaPage(listOf(UnifiedMediaItem(MediaKey(id, "item"), "test", "Movie")), "next")
        }
        override suspend fun detail(key: MediaKey) = error("not used")
        override suspend fun resolve(request: UnifiedPlaybackRequest) = listOf(candidate)
    }
    @Test fun `legacy preserves candidate and distinguishes live`() = runBlocking {
        val adapter = LegacyOpenProvider(provider())
        val video = adapter.open(OpenRequest(MediaKey("p", "item"), ContentKind.MOVIE)) as OpenPlan.Video
        assertSame(candidate, video.candidates.single())
        assertTrue(adapter.open(OpenRequest(MediaKey("p", "item"), ContentKind.LIVE_CHANNEL)) is OpenPlan.Live)
    }
    @Test fun `fast source emits before slow source and preserves cursor`() = runBlocking {
        val snapshots = AggregateSearchEngine(500).searchSnapshots(
            listOf(provider("slow", 80), provider("fast", 1)), ProviderSearchRequest("test")
        ).toList()
        assertEquals(3, snapshots.size)
        assertEquals("fast", snapshots[1].items.single().key.providerId)
        assertEquals("next", snapshots[1].nextPageTokens["fast"])
        assertTrue(snapshots.last().pendingProviderIds.isEmpty())
    }
    @Test fun `slow source is timed out independently`() = runBlocking {
        val result = AggregateSearchEngine(20).search(listOf(provider("slow", 200)), ProviderSearchRequest("test"))
        assertTrue(result.items.isEmpty())
        assertEquals("slow", result.failures.single().providerId)
    }
}
