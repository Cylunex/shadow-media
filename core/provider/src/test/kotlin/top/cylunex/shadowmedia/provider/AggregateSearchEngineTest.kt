package top.cylunex.shadowmedia.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cylunex.shadowmedia.model.MediaDetail
import top.cylunex.shadowmedia.model.MediaKey
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.model.ProviderCapability
import top.cylunex.shadowmedia.model.ProviderDescriptor
import top.cylunex.shadowmedia.model.ProviderKind
import top.cylunex.shadowmedia.model.UnifiedMediaItem
import top.cylunex.shadowmedia.model.UnifiedMediaPage
import top.cylunex.shadowmedia.model.UnifiedPlaybackRequest

class AggregateSearchEngineTest {
    @Test
    fun `merges partial results and ranks exact title first`() = runBlocking {
        val exact = SearchProvider("a", "源 A", listOf(item("a", "银河")))
        val partial = SearchProvider("b", "源 B", listOf(item("b", "银河护卫队")))
        val broken = SearchProvider("c", "源 C", emptyList(), IllegalStateException("offline"))

        val result = AggregateSearchEngine().search(
            listOf(partial, broken, exact),
            ProviderSearchRequest("银河"),
        )

        assertEquals("银河", result.items.first().title)
        assertEquals(2, result.items.size)
        assertEquals("c", result.failures.single().providerId)
        assertTrue(result.failures.single().message.contains("offline"))
    }

    private fun item(providerId: String, title: String) = UnifiedMediaItem(
        key = MediaKey(providerId, title),
        title = title,
        type = "Movie",
    )
}

private class SearchProvider(
    id: String,
    name: String,
    private val items: List<UnifiedMediaItem>,
    private val failure: Throwable? = null,
) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id,
        name,
        ProviderKind.DECLARATIVE_HTTP,
        setOf(ProviderCapability.SEARCH),
    )

    override suspend fun home() = emptyList<ProviderSection>()
    override suspend fun browse(request: ProviderBrowseRequest) = UnifiedMediaPage(emptyList())
    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
        failure?.let { throw it }
        return UnifiedMediaPage(items)
    }
    override suspend fun detail(key: MediaKey) = MediaDetail(items.first { it.key == key })
    override suspend fun resolve(request: UnifiedPlaybackRequest) = emptyList<PlaybackCandidate>()
}
