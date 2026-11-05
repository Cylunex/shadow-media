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
    @Test fun `shared account refreshes once for api and poster without storing a mobile token`() = runTest {
        MockWebServer().use { server ->
            val refreshes = mutableListOf<Boolean>()
            val connection = CatalogConnection(id = "shared-mdc:account", name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/novel/services/mdc/").toString(), allowHttp = true)
            val provider = MdcMediaProvider(connection, OkHttpClient()) { force ->
                refreshes.add(force); if (force) "fresh-session" else "existing-session"
            }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":null}"""))
            provider.browse(ProviderBrowseRequest())
            assertEquals(listOf(false, true), refreshes)
            assertEquals("Bearer existing-session", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-session", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("poster"))
            provider.imageClient.newCall(okhttp3.Request.Builder()
                .url(server.url("/novel/services/mdc/api/media/v1/works/a/poster")).build()).execute().close()
            assertEquals("Bearer existing-session", server.takeRequest().getHeader("Authorization"))
            assertTrue(connection.token.isEmpty())
            assertTrue(connection.oidcAuthState.isEmpty())
            assertTrue(runCatching { provider.imageClient.newCall(okhttp3.Request.Builder()
                .url(server.url("/novel/auth/me")).build()).execute().close() }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `shared service discovery rejects other origins paths and plain http`() {
        val account = CatalogConnection(id = "account", name = "Novel", kind = CatalogKind.OPDS,
            url = "https://example.com/novel/", oidcIssuer = "https://identity.example.com", oidcAuthState = "private")
        fun manifest(url: String) = org.json.JSONObject().put("services", org.json.JSONArray().put(
            org.json.JSONObject().put("id", "mdc").put("base_url", url)))
        val service = sharedMdcConnection(account, manifest("https://example.com/novel/services/mdc/"))!!
        assertEquals("shared-mdc:account", service.id)
        assertTrue(service.oidcAuthState.isEmpty())
        assertNull(sharedMdcConnection(account, org.json.JSONObject()))
        for (url in listOf("https://other.example.com/novel/services/mdc/", "https://example.com/other/",
            "http://example.com/novel/services/mdc/", "https://example.com/novel/services/mdc/?token=x")) {
            assertTrue(runCatching { sharedMdcConnection(account, manifest(url)) }.isFailure)
        }
    }

    @Test fun `expired shared session fails instead of falling back to a mobile credential`() = runTest {
        MockWebServer().use { server ->
            var attempts = 0
            val provider = MdcMediaProvider(CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), allowHttp = true, token = "must-not-use"), OkHttpClient()) {
                attempts++
                throw java.io.IOException("signed out")
            }
            assertTrue(runCatching { provider.checkConnection() }.isFailure)
            assertEquals(1, attempts)
            assertEquals(0, server.requestCount)
        }
    }

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
