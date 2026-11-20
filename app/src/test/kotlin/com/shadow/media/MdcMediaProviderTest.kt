package com.shadow.media

import com.shadow.media.library.CatalogConnection
import com.shadow.media.library.CatalogKind
import com.shadow.media.model.MediaKey
import com.shadow.media.provider.ProviderBrowseRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
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
    @Test fun cancellingASlowCatalogueRequestAllowsImmediateReload() = runBlocking {
        MockWebServer().use { server ->
            val provider = MdcMediaProvider(CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/").toString(), allowHttp = true), OkHttpClient())
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val stalled = launch(kotlinx.coroutines.Dispatchers.IO) { provider.browse(ProviderBrowseRequest()) }
            assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(3000) { stalled.cancelAndJoin() }
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            assertTrue(provider.browse(ProviderBrowseRequest()).items.isEmpty())
        }
    }

    @Test fun discoveryIsolatesAccountsAndRevalidatesCachedServices() = runTest {
        val first = CatalogConnection(id = "one", name = "One", kind = CatalogKind.OPDS, url = "https://one.example.com/",
            oidcIssuer = "https://id.example.com", oidcSubject = "owner", oidcAuthState = "session")
        val second = first.copy(id = "two", name = "Two", url = "https://two.example.com/")
        var accounts = listOf(first, second)
        var firstStatus = 503
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val code = if (request.url.host == "one.example.com") firstStatus else 200
            val response = if (code == 200) """{"services":[{"id":"mdc","base_url":"https://${request.url.host}/services/mdc/"}]}""" else "{}"
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code)
                .message("fixture").body(response.toResponseBody()).build()
        }.build()
        val api = com.shadow.media.library.NovelApi(client) { _, _ -> "session" }
        val novel = com.shadow.media.library.NovelRepository(org.robolectric.RuntimeEnvironment.getApplication(), api = api,
            connectionLoader = { accounts })
        val sessions = SharedMdcSessions(novel)
        fun ids() = sessions.connections().map { it.id }
        assertEquals(listOf("One"), sessions.discover())
        assertEquals(listOf("shared-mdc:two"), ids())
        firstStatus = 200
        assertTrue(sessions.discover().isEmpty())
        firstStatus = 503
        assertTrue(sessions.discover().isEmpty())
        assertEquals(listOf("One"), sessions.discover(force = true))
        assertEquals(listOf("shared-mdc:one", "shared-mdc:two"), ids())
        accounts = listOf(first.copy(oidcSubject = "different"), second)
        assertEquals(listOf("shared-mdc:two"), ids())
        firstStatus = 404
        assertTrue(sessions.discover().isEmpty())
        val manual = second.copy(id = "manual")
        val isolated = readMdcConnections({ listOf(manual) }, { error("locked") })
        assertEquals(listOf(manual), isolated.connections)
        assertEquals(listOf("统一账号"), isolated.failedAccounts)
        assertEquals(ids(), readMdcConnections({ error("locked") }, sessions::connections).connections.map { it.id })
    }

    @Test fun remoteSubtitlesUseOnlyThisWorkAndTheAuthenticatedGateway() = runTest {
        val connection = CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
            url = "https://example.com/library/", token = "REPLACE_TOKEN")
        val provider = MdcMediaProvider(connection, OkHttpClient())
        val key = com.shadow.media.model.MediaKey(provider.descriptor.id, "work1")
        val prefix = "/api/media/v1/works/work1/subtitles/"
        val hash = "a".repeat(32)
        val tracks = org.json.JSONArray()
        for (path in listOf(prefix + hash, prefix + "../other", prefix.replace("work1", "other") + hash,
                "https://other.example.com/" + hash)) tracks.put(org.json.JSONObject().put("url", path).put("label", "example.srt"))
        val track = provider.workSubtitles(key, org.json.JSONObject().put("subtitles", tracks)).single()
        assertEquals("https://example.com/library" + prefix + hash, track.uri)
        assertEquals("Bearer REPLACE_TOKEN", track.requestHeaders["Authorization"])
        assertEquals(connection.url, track.credentialOrigin)
    }
    @Test fun `default filters remove all collection constraints`() {
        val active = MdcFilters(label = "label", platform = "platform", series = "series").request("sample")
        for (key in listOf("series", "platforms", "labels")) assertTrue(active.has(key))
        val cleared = MdcFilters().request("")
        for (key in listOf("series", "platforms", "labels", "actors", "year_from", "year_to")) assertFalse(cleared.has(key))
        assertEquals("", cleared.getString("q"))
    }

    @Test fun `shared account refreshes once for api and poster without storing a mobile token`() = runTest {
        MockWebServer().use { server ->
            val refreshes = mutableListOf<String?>()
            val connection = CatalogConnection(id = "shared-mdc:account", name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/novel/services/mdc/").toString(), allowHttp = true)
            val provider = MdcMediaProvider(connection, OkHttpClient()) { rejected ->
                refreshes.add(rejected); if (rejected != null) "fresh-session" else "existing-session"
            }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":null}"""))
            provider.browse(ProviderBrowseRequest())
            assertEquals(listOf(null, "existing-session"), refreshes)
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

    @Test fun `shared playlist namespace is valid stable across devices and isolated by identity`() {
        val account = CatalogConnection(id = "device-a", name = "Novel", kind = CatalogKind.OPDS,
            url = "https://example.com/novel/", oidcIssuer = "https://identity.example.com", oidcSubject = "owner")
        val manifest = org.json.JSONObject("""{"services":[{"id":"mdc","base_url":"https://example.com/novel/services/mdc/"}]}""")
        fun owner(c: CatalogConnection) = sharedMdcConnection(c, manifest)!!.username
        assertTrue(owner(account).matches(Regex("[A-Za-z0-9_-]{1,100}")))
        assertEquals(owner(account), owner(account.copy(id = "device-b", oidcAuthState = "refreshed")))
        assertNotEquals(owner(account), owner(account.copy(oidcSubject = "other")))
        assertNotEquals(owner(account), owner(account.copy(oidcIssuer = "https://other.example.com")))
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
