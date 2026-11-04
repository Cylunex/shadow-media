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
    @Test fun `online resume uses the key already saved for this connection and version`() {
        val key = externalHistoryKey("mdc:library-a", "work-a:version:disc-one")
        assertEquals("external:mdc:library-a:work-a:version:disc-one", key.stableId)
        assertNotEquals(key, externalHistoryKey("mdc:library-a", "work-a:version:disc-two"))
        assertNotEquals(key, externalHistoryKey("mdc:library-b", key.itemId))
        assertEquals("emby:owner:film", externalHistoryKey("emby:owner", "film").stableId)
    }

    @Test fun `recovery refreshes only the same confirmed version`() {
        val plan = org.json.JSONObject("""{"sources":[
            {"version_id":"cd2","source_id":"other","url":"https://example.com/two.m3u8","playable_in_app":true},
            {"version_id":"cd1","source_id":"original","url":"https://example.com/refreshed.m3u8","playable_in_app":true}
        ]}""")
        assertEquals("https://example.com/refreshed.m3u8",
            selectMdcRecoverySource(plan, "cd1", "original")!!.getString("url"))
        assertNull(selectMdcRecoverySource(plan, "director-cut", "original"))
    }

    @Test fun `connection preferences preserve identity and rotate it with credentials`() {
        val store = com.shadow.media.library.CatalogConnectionStore(
            org.robolectric.RuntimeEnvironment.getApplication(),
            "mdc-test-" + java.util.UUID.randomUUID(), "fixture",
            keyProvider = { javax.crypto.spec.SecretKeySpec(ByteArray(16) { 3 }, "AES") },
        )
        val connection = CatalogConnection(
            id = "connection", name = "MDC", kind = CatalogKind.OPDS,
            url = "https://example.com", token = "REPLACE_TOKEN", includeInSearch = true,
        )
        store.save(connection)
        assertTrue(store.load().single().includeInSearch)
        val edited = store.replace(connection, connection.copy(includeInSearch = false))
        assertEquals(connection.id, edited.id)
        assertFalse(store.load().single().includeInSearch)
        val rotated = store.replace(edited, edited.copy(token = "REPLACE_NEW_TOKEN"))
        assertNotEquals(edited.id, rotated.id)
    }

}
