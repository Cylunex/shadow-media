package com.shadow.media.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.shadow.media.model.MediaDetail
import com.shadow.media.model.MediaKey
import com.shadow.media.model.PlaybackCandidate
import com.shadow.media.model.ProviderCapability
import com.shadow.media.model.ProviderDescriptor
import com.shadow.media.model.ProviderKind
import com.shadow.media.model.UnifiedMediaItem
import com.shadow.media.model.UnifiedMediaPage
import com.shadow.media.model.UnifiedPlaybackRequest

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
