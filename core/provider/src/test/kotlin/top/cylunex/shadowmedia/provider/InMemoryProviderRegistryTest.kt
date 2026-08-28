package top.cylunex.shadowmedia.provider

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.cylunex.shadowmedia.model.MediaDetail
import top.cylunex.shadowmedia.model.MediaKey
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.model.ProviderCapability
import top.cylunex.shadowmedia.model.ProviderDescriptor
import top.cylunex.shadowmedia.model.ProviderKind
import top.cylunex.shadowmedia.model.UnifiedMediaPage
import top.cylunex.shadowmedia.model.UnifiedPlaybackRequest

class InMemoryProviderRegistryTest {
    @Test
    fun `replace deduplicates provider ids and supports lookup`() = runBlocking {
        val first = FakeProvider("emby")
        val replacement = FakeProvider("emby")
        val live = FakeProvider("live")
        val registry = InMemoryProviderRegistry()

        registry.replace(listOf(first, replacement, live))

        assertEquals(listOf("emby", "live"), registry.providers.first().map { it.descriptor.id })
        assertEquals(first, registry.provider("emby"))
        assertNull(registry.provider("missing"))
    }
}

private class FakeProvider(id: String) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = id,
        name = id,
        kind = ProviderKind.DECLARATIVE_HTTP,
        capabilities = setOf(ProviderCapability.BROWSE),
    )

    override suspend fun home(): List<ProviderSection> = emptyList()
    override suspend fun browse(request: ProviderBrowseRequest) = UnifiedMediaPage(emptyList())
    override suspend fun search(request: ProviderSearchRequest) = UnifiedMediaPage(emptyList())
    override suspend fun detail(key: MediaKey): MediaDetail = error("unused")
    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> = emptyList()
}
