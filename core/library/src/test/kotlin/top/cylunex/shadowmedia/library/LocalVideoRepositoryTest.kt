package top.cylunex.shadowmedia.library

import android.content.Context
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.cylunex.shadowmedia.model.UnifiedPlaybackRequest
import top.cylunex.shadowmedia.provider.ProviderBrowseRequest
import top.cylunex.shadowmedia.provider.ProviderSearchRequest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LocalVideoRepositoryTest {
    private lateinit var context: Context
    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("local_videos", 0).edit().clear().commit()
    }
    @Test fun `saved live links survive recreation and refresh without storage permission`() = runBlocking {
        val first = LocalVideoRepository(context)
        val video = first.addLink("https://example.com/live/index.m3u8?session=example")
        first.addLink("https://example.com/live/index.m3u8?session=example")
        val restored = LocalVideoRepository(context)
        restored.refresh()
        assertEquals(1, restored.videos().size)
        assertEquals(video.key, restored.detail(video.key).item.key)
        val candidate = restored.resolve(UnifiedPlaybackRequest(video.key, 12_000)).single()
        assertEquals(video.key.itemId, candidate.url)
        assertTrue(candidate.requiredHeaders.isEmpty())
    }
    @Test fun `concurrent additions retain every entry and browse search paginate`() = runBlocking {
        val repository = LocalVideoRepository(context)
        coroutineScope { (1..20).map { async { repository.addLink("https://example.com/film$it.mp4") } }.awaitAll() }
        assertEquals(20, repository.videos().size)
        val page = repository.browse(ProviderBrowseRequest(pageSize = 7))
        val next = repository.browse(ProviderBrowseRequest(pageToken = page.nextPageToken, pageSize = 7))
        assertEquals(7, page.items.size)
        assertEquals(20, page.totalCount)
        assertTrue(page.items.map { it.key }.intersect(next.items.map { it.key }.toSet()).isEmpty())
        assertEquals(1, repository.search(ProviderSearchRequest("film20")).items.size)
    }
    @Test fun `rejects non network schemes and embedded credentials before persisting`() = runBlocking {
        val repository = LocalVideoRepository(context)
        for (url in listOf("file:///private/movie.mp4", "javascript:alert(1)", "https://user:secret@example.com/a", "http://")) {
            try { repository.addLink(url); fail("Accepted $url") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(repository.videos().isEmpty())
    }
}
