package com.shadow.media

import com.shadow.media.library.CatalogConnection
import com.shadow.media.library.CatalogKind
import com.shadow.media.model.MediaKey
import com.shadow.media.provider.ProviderBrowseRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class MdcMediaProviderTest {
    @Test fun `prefix credentials cursor and exact playback target survive catalog mapping`() = runTest {
        MockWebServer().use { server ->
            val provider = MdcMediaProvider(CatalogConnection(
                id = "library-a", name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), token = "REPLACE_TOKEN", allowHttp = true,
            ), OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"work-a","title":"A","code":"ABC-123","actors":["Actor"],"duration_ms":2000,"poster":"/api/media/v1/works/work-a/poster"}],"next_cursor":"cursor-a"}"""))
            val page = provider.browse(ProviderBrowseRequest())
            val browse = server.takeRequest()
            assertEquals("/mdc/api/media/v1/works", browse.requestUrl!!.encodedPath)
            assertEquals("Bearer REPLACE_TOKEN", browse.getHeader("Authorization"))
            assertEquals("cursor-a", page.nextPageToken)
            assertEquals(MediaKey("mdc:library-a", "work-a"), page.items.single().key)
            assertTrue(page.items.single().posterUrl!!.contains("/mdc/api/media/v1/works/"))
            server.enqueue(MockResponse().setBody("""{"server_id":"emby-a","item_id":"exact-id"}"""))
            assertEquals("emby-a" to "exact-id", provider.embyTarget(page.items.single().key))
            val resolve = server.takeRequest()
            assertEquals("POST", resolve.method)
            assertEquals("/mdc/api/media/v1/works/work-a/resolve", resolve.path)
            server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":null}"""))
            provider.browse(ProviderBrowseRequest(pageToken = page.nextPageToken))
            assertEquals("cursor-a", server.takeRequest().requestUrl!!.queryParameter("cursor"))
        }
    }

    @Test fun `redirect never forwards mobile credentials to another host`() = runTest {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            val provider = MdcMediaProvider(CatalogConnection(
                name = "MDC", kind = CatalogKind.OPDS, url = origin.url("/").toString(),
                token = "REPLACE_TOKEN", allowHttp = true,
            ), OkHttpClient())
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/")))
            assertTrue(runCatching { provider.checkConnection() }.isFailure)
            assertEquals(0, other.requestCount)
        } }
    }
}
