package top.cylunex.shadowmedia.library

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.network.DefaultNetworkStorageRepository

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WebDavVideoSubtitlesTest {
    private val listing = """
        <d:multistatus xmlns:d="DAV:">
        <d:response><d:href>/dav/Film.mkv</d:href><d:propstat><d:prop><d:getcontentlength>1000</d:getcontentlength></d:prop></d:propstat></d:response>
        <d:response><d:href>/dav/Film.zh.srt</d:href><d:propstat><d:prop><d:getcontentlength>30</d:getcontentlength></d:prop></d:propstat></d:response>
        <d:response><d:href>/dav/Film.ass</d:href><d:propstat><d:prop><d:getcontentlength>30</d:getcontentlength></d:prop></d:propstat></d:response>
        <d:response><d:href>/dav/Film.en.vtt</d:href><d:propstat><d:prop><d:getcontentlength>30</d:getcontentlength></d:prop></d:propstat></d:response>
        <d:response><d:href>/dav/Film2.srt</d:href><d:propstat><d:prop><d:getcontentlength>30</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>
    """.trimIndent()
    @Test fun `resolves same name subtitles alongside authenticated WebDAV video`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(207).setBody(listing)
        }
        try {
            val connection = NetworkStorageConnection("subtitle-test", "DAV", NetworkStorageKind.WEBDAV,
                server.url("/dav/").toString(), username = "example", password = "example", allowInsecureHttp = true)
            val provider = DefaultNetworkStorageRepository(RuntimeEnvironment.getApplication(), OkHttpClient()).provider(connection)
            val candidate = provider.resolve(UnifiedPlaybackRequest(MediaKey("storage:${connection.id}", "/Film.mkv"))).single()
            assertEquals(server.url("/dav/Film.mkv").toString(), candidate.url)
            assertEquals(setOf("Film.zh.srt", "Film.ass", "Film.en.vtt"), candidate.subtitles.map { it.label }.toSet())
            assertEquals(setOf("application/x-subrip", "text/x-ssa", "text/vtt"), candidate.subtitles.map { it.mimeType }.toSet())
            assertTrue(candidate.requiredHeaders["Authorization"].orEmpty().startsWith("Basic "))
            assertTrue(candidate.subtitles.all { it.uri.startsWith(server.url("/dav/").toString()) })
        } finally { server.shutdown() }
    }
    @Test fun `failed subtitle listing still returns the playable video`() = runBlocking {
        val server = MockWebServer()
        var requests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (++requests == 1) MockResponse().setResponseCode(207).setBody(listing)
                else MockResponse().setResponseCode(503)
        }
        try {
            val connection = NetworkStorageConnection("subtitle-failure", "DAV", NetworkStorageKind.WEBDAV,
                server.url("/dav/").toString(), allowInsecureHttp = true)
            val provider = DefaultNetworkStorageRepository(RuntimeEnvironment.getApplication(), OkHttpClient()).provider(connection)
            val candidate = provider.resolve(UnifiedPlaybackRequest(MediaKey("storage:${connection.id}", "/Film.mkv"))).single()
            assertEquals(server.url("/dav/Film.mkv").toString(), candidate.url)
            assertTrue(candidate.subtitles.isEmpty())
        } finally { server.shutdown() }
    }
}
