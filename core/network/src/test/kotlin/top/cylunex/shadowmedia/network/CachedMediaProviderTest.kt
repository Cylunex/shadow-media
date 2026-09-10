package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.*

class CachedMediaProviderTest {
    private class Remote : MediaProvider {
        override val descriptor = ProviderDescriptor("storage:a", "A", ProviderKind.WEBDAV, emptySet())
        var calls = 0
        var fail = false
        var rows = listOf(UnifiedMediaItem(MediaKey(descriptor.id, "/Movie.mp4"), "Movie", "Movie", posterUrl = "https://example.com/image?token=private"))
        override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage { calls++; check(!fail); return UnifiedMediaPage(rows, "60", 61) }
        override suspend fun detail(key: MediaKey) = MediaDetail(rows.first(), rows, childrenNextPageToken = "60")
        override suspend fun search(request: ProviderSearchRequest) = browse(ProviderBrowseRequest())
        override suspend fun home() = emptyList<ProviderSection>()
        override suspend fun resolve(request: UnifiedPlaybackRequest) = listOf(PlaybackCandidate("https://example.com/stream?token=lease", PlayMethod.DIRECT_PLAY, emptyMap()))
    }
    private fun cache(remote: Remote, failure: (String) -> Throwable? = { null }): Pair<CachedMediaProvider, MutableMap<String, CatalogPageEntity>> {
        val pages = mutableMapOf<String, CatalogPageEntity>()
        val dao = Proxy.newProxyInstance(LibraryStateDao::class.java.classLoader, arrayOf(LibraryStateDao::class.java)) { _, method, args ->
            failure(method.name)?.let { throw it }
            when(method.name) {
            "catalogPage" -> pages[args[0]]
            "cacheCatalogPage" -> { (args[0] as CatalogPageEntity).also { pages[it.id] = it }; Unit }
            else -> error(method.name)
        } } as LibraryStateDao
        return CachedMediaProvider(remote, dao) to pages
    }
    @Test fun preservesFreshArtworkButCachesOnlyMetadataAndFallsBackOffline() = runBlocking {
        val remote = Remote(); val (cached, pages) = cache(remote); val request = ProviderBrowseRequest()
        assertEquals(remote.rows, cached.browse(request).items)
        assertFalse(pages.values.single().payload.contains("private"))
        remote.fail = true
        assertTrue(cached.browse(request).cached)
        try {
            NetworkPolicy.offlineOnly = true; val before = remote.calls
            val offline = cached.browse(request)
            assertEquals(before, remote.calls); assertEquals("60", offline.nextPageToken); assertNull(offline.items.single().posterUrl)
        } finally { NetworkPolicy.offlineOnly = false }
        assertTrue(cached.resolve(UnifiedPlaybackRequest(remote.rows.first().key)).single().url.contains("lease"))
    }
    @Test fun failedAndEmptyRefreshRetainLastNonEmptyDirectory() = runBlocking {
        val remote = Remote(); val (cached, _) = cache(remote); val request = ProviderBrowseRequest()
        cached.browse(request); remote.rows = emptyList()
        assertTrue(cached.browse(request).cached)
        assertEquals("Movie", cached.cachedBrowse(request)!!.items.single().title)
    }
    @Test fun failedCacheWriteDoesNotReplaceFreshDirectoryWithStaleMetadata() = runBlocking {
        var writeFails = false
        val remote = Remote()
        val (cached, _) = cache(remote) { if (writeFails && it == "cacheCatalogPage") IllegalStateException("disk full") else null }
        val request = ProviderBrowseRequest()
        cached.browse(request)
        writeFails = true
        remote.rows = remote.rows.map { it.copy(title = "New") }
        val fresh = cached.browse(request)
        assertFalse(fresh.cached)
        assertEquals("New", fresh.items.single().title)
        assertEquals("Movie", cached.cachedBrowse(request)!!.items.single().title)
    }
    @Test fun readFailureDoesNotPreventOnlineDetailOrBrowse() = runBlocking {
        val remote = Remote()
        val (cached, _) = cache(remote) { if (it == "catalogPage") IllegalStateException("cache unavailable") else null }
        assertEquals(remote.rows, cached.browse(ProviderBrowseRequest()).items)
        assertEquals(remote.rows.first(), cached.detail(remote.rows.first().key).item)
    }
    @Test fun foreignAccountCannotReadDetailOrBrowseThroughCache() = runBlocking {
        val remote = Remote(); val (cached, _) = cache(remote)
        cached.detail(remote.rows.first().key)
        val foreign = remote.rows.first().key.copy(providerId = "storage:b")
        assertTrue(runCatching { cached.cachedDetail(foreign) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { cached.cachedBrowse(ProviderBrowseRequest(foreign)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { cached.browse(ProviderBrowseRequest(foreign)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, remote.calls)
    }
}
