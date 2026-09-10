package top.cylunex.shadowmedia.library

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.*
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NativeMusicProviderTest {
    @Test fun jellyfinPlaybackInfoBuildsCandidateChainWithoutProbingMedia() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"Id":"user"}"""))
            server.enqueue(MockResponse().setBody("""{"Id":"song","Type":"Audio","MediaType":"Audio"}"""))
            server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"original","SupportsDirectPlay":true,"SupportsTranscoding":true,"TranscodingUrl":"/Audio/song/stream.mp3?AudioCodec=mp3"}]}"""))
            val provider = NativeMusicProvider(CatalogConnection(name = "Music", kind = CatalogKind.JELLYFIN, url = server.url("/").toString(), token = "example", allowHttp = true))
            val candidates = provider.resolve(UnifiedPlaybackRequest(MediaKey(provider.descriptor.id, "song")))
            assertEquals(listOf(PlayMethod.DIRECT_PLAY, PlayMethod.TRANSCODE), candidates.map { it.method })
            assertEquals("original", candidates.first().mediaSourceId)
            repeat(2) { server.takeRequest() }
            val request = server.takeRequest()
            assertEquals("/Items/song/PlaybackInfo", request.requestUrl!!.encodedPath); assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("DeviceProfile")); assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun jellyfinUsesNativeRoutesModernAuthorizationAndStableSongIdentity() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"AccessToken":"example-token","User":{"Id":"user"}}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"song","Type":"Audio","Name":"曲目","Album":"专辑","AlbumId":"album","Artists":["艺人"],"ParentIndexNumber":2,"IndexNumber":3,"RunTimeTicks":50000000}],"TotalRecordCount":1}"""))
            val provider = NativeMusicProvider(CatalogConnection(name = "Jellyfin", kind = CatalogKind.JELLYFIN, url = server.url("/jellyfin/").toString(), username = "user", password = "secret", allowHttp = true))
            val page = provider.browse(ProviderBrowseRequest(MediaKey(provider.descriptor.id, "songs")))
            val login = server.takeRequest(); assertEquals("/jellyfin/Users/AuthenticateByName", login.path)
            assertTrue(login.getHeader("Authorization")!!.startsWith("MediaBrowser ")); assertEquals("POST", login.method)
            val request = server.takeRequest(); assertEquals("/jellyfin/Items", request.requestUrl!!.encodedPath)
            assertTrue(request.getHeader("Authorization")!!.contains("Token=\"example-token\"")); assertNull(request.getHeader("X-Emby-Token"))
            assertEquals("Audio", request.requestUrl!!.queryParameter("includeItemTypes"))
            assertEquals("MUSIC", page.items.single().type); assertEquals(2, page.items.single().music!!.disc)
            assertEquals(5000L, page.items.single().durationMs); assertNull(page.nextPageToken)
            assertFalse(page.items.single().key.itemId.contains("token"))
        } finally { server.shutdown() }
    }
    @Test fun subsonicUsesFreshSaltHashOrApiKeyWithoutConflictingCredentials() {
        val c = CatalogConnection(name = "Music", kind = CatalogKind.OPENSUBSONIC, url = "https://example.com/music/", username = "user", password = "secret")
        val provider = NativeMusicProvider(c)
        val first = provider.subsonicUrl("stream", listOf("id" to "song")); val second = provider.subsonicUrl("stream")
        assertEquals("/music/rest/stream.view", first.encodedPath); assertNull(first.queryParameter("p"))
        assertNotEquals(first.queryParameter("s"), second.queryParameter("s"))
        val expected = MessageDigest.getInstance("MD5").digest(("secret" + first.queryParameter("s")).toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, first.queryParameter("t"))
        val api = NativeMusicProvider(c.copy(token = "example-key")).subsonicUrl("ping")
        assertEquals("example-key", api.queryParameter("apiKey")); assertNull(api.queryParameter("u")); assertNull(api.queryParameter("t"))
    }
    @Test fun albumLookaheadFinishesSongIndexOnLastNonemptyPage() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val provider = NativeMusicProvider(CatalogConnection(name = "Music", kind = CatalogKind.OPENSUBSONIC, url = server.url("/").toString(), token = "example", allowHttp = true))
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"album"}]}}}"""))
                server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","album":{"song":[{"id":"one","title":"一","discNumber":1,"track":1},{"id":"two","title":"二","discNumber":1,"track":2}]}}}"""))
            }
            val first = provider.browse(ProviderBrowseRequest(MediaKey(provider.descriptor.id, "songs"), pageSize = 1))
            val last = provider.browse(ProviderBrowseRequest(MediaKey(provider.descriptor.id, "songs"), pageToken = first.nextPageToken, pageSize = 1))
            assertEquals("0:1", first.nextPageToken); assertEquals("two", last.items.single().key.itemId); assertNull(last.nextPageToken)
        } finally { server.shutdown() }
    }
    @Test fun apiRedirectCannotTransmitCredentialsToAnotherOrigin() = runBlocking {
        val api = MockWebServer(); val other = MockWebServer(); api.start(); other.start()
        try {
            api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/rest/getSong.view?apiKey=example")))
            val provider = NativeMusicProvider(CatalogConnection(name = "Music", kind = CatalogKind.OPENSUBSONIC, url = api.url("/").toString(), token = "example", allowHttp = true))
            try { provider.detail(MediaKey(provider.descriptor.id, "song")); fail("redirect must be rejected") } catch (_: IOException) { }
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { api.shutdown(); other.shutdown() }
    }
    @Test fun removingAccountProducesDifferentStableScope() {
        val c = CatalogConnection(name = "A", kind = CatalogKind.OPENSUBSONIC, url = "https://example.com/", token = "example")
        assertNotEquals(NativeMusicProvider(c).descriptor.id, NativeMusicProvider(c.copy(id = "new-account")).descriptor.id)
    }
}
