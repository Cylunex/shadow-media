package com.shadow.media

import com.shadow.media.library.CatalogConnection
import com.shadow.media.library.CatalogKind
import com.shadow.media.model.UnifiedPlaybackRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [28], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class MdcTvBoxProviderTest {
    @Test fun `resolves existing episode after cold start without passing catalog credentials to player`() = runTest {
        MockWebServer().use { server ->
            val connection = CatalogConnection(
                id = "mdc-test", name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), allowHttp = true, token = "REPLACE_MDC_TOKEN",
            )
            fun provider() = MdcTvBoxProvider(MdcMediaProvider(connection, OkHttpClient()))
            server.enqueue(MockResponse().setBody("""{"features":["tvbox_v1"]}"""))
            assertTrue(provider().supported())
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"station","name":"Demo"}]}"""))
            assertEquals("station", provider().sites().single().id)
            server.enqueue(MockResponse().setBody("""
                {"items":[{"site_id":"station","id":"demo","title":"Demo series"}],"next_page":2}
            """.trimIndent()))
            val page = provider().items("station", "category", "1", "demo")
            assertEquals("2", page.nextPageToken)
            server.enqueue(MockResponse().setBody("""
                {"site_id":"station","id":"demo","title":"Demo series","episodes":[
                    {"id":"episode","title":"First","line":"Primary"}
                ]}
            """.trimIndent()))
            val key = provider().detail(page.items.single().key).children.single().key
            assertEquals("station:demo:episode", key.itemId)
            assertEquals(key, externalHistoryKey(key.providerId, key.itemId))

            server.enqueue(MockResponse().setBody("""
                {"url":"https://example.com/lease","mime_type":"application/vnd.apple.mpegurl",
                 "headers":{"Referer":"https://example.com/","Authorization":"discard-me"}}
            """.trimIndent()))
            val stream = provider().resolve(UnifiedPlaybackRequest(key)).single()
            assertEquals("https://example.com/lease", stream.url)
            assertEquals("application/vnd.apple.mpegurl", stream.mimeType)
            assertEquals(mapOf("Referer" to "https://example.com/"), stream.requiredHeaders)
            val requests = List(5) { server.takeRequest() }
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer REPLACE_MDC_TOKEN" })
            assertEquals("/mdc/api/media/v1/tvbox/sites/station/items/demo/resolve", requests.last().path)
            assertEquals("""{"episode_id":"episode"}""", requests.last().body.readUtf8())
        }
    }

    @Test fun `capability discovery hides native entry for older MDC deployments`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"features":["catalog","playback_plan"]}"""))
            val connection = CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), allowHttp = true)
            assertFalse(MdcTvBoxProvider(MdcMediaProvider(connection, OkHttpClient())).supported())
        }
    }
}
