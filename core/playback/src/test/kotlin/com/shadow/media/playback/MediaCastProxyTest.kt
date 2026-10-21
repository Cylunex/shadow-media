package com.shadow.media.playback

import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
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
import com.shadow.media.model.ExternalMediaEntry

@RunWith(RobolectricTestRunner::class) @Config(manifest = Config.NONE, sdk = [28])
class MediaCastProxyTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val client = OkHttpClient()
    @Test fun `file bridge serves bounded inclusive ranges and HEAD then closes its listener`() {
        val file = java.io.File(context.cacheDir, "cast.mp4").apply { writeText("0123456789") }
        val proxy = MediaCastProxy(context, ExternalMediaEntry("id", "local", "movie", file.toURI().toString()), loopback, loopback, null)
        client.newCall(Request.Builder().url(proxy.url).header("Range", "bytes=3-6").build()).execute().use {
            assertEquals(206, it.code); assertEquals("bytes 3-6/10", it.header("Content-Range")); assertEquals("3456", it.body!!.string())
        }
        client.newCall(Request.Builder().url(proxy.url).head().build()).execute().use { assertEquals("10", it.header("Content-Length")) }
        client.newCall(Request.Builder().url(proxy.url).header("Range", "bytes=-3").build()).execute().use { assertEquals("789", it.body!!.string()) }
        proxy.close()
        try { client.newCall(Request.Builder().url(proxy.url).build()).execute().close(); fail() } catch (_: java.io.IOException) { }
    }
    @Test fun `opaque bridge rejects unknown resource tokens before touching media`() {
        val proxy = MediaCastProxy(context, ExternalMediaEntry("id", "local", "movie", "file:///does-not-exist.mp4"), loopback, loopback, null)
        proxy.use { client.newCall(Request.Builder().url(it.url.substringBeforeLast('/') + "/unknown").build()).execute().use { response -> assertEquals(404, response.code) } }
    }
    @Test fun `redirected media does not send origin credentials to another server`() {
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            cdn.enqueue(MockResponse().setBody("video").setHeader("Content-Type", "video/mp4")); cdn.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/video.mp4"))); origin.start()
            val entry = ExternalMediaEntry("id", "source", "movie", origin.url("/video.mp4").toString(), requestHeaders = mapOf("Authorization" to "Bearer secret", "X-Emby-Token" to "secret"), credentialOrigin = origin.url("/").toString())
            MediaCastProxy(context, entry, loopback, loopback, null).use { proxy ->
                assertFalse(proxy.url.contains("secret"))
                client.newCall(Request.Builder().url(proxy.url).build()).execute().use { assertEquals("video", it.body!!.string()) }
            }
            assertEquals("secret", origin.takeRequest().getHeader("X-Emby-Token"))
            val request = cdn.takeRequest(); assertNull(request.getHeader("X-Emby-Token")); assertNull(request.getHeader("Authorization"))
        } }
    }
    @Test fun `HLS manifests segments and key URIs are all bridged without exposing signatures`() {
        MockWebServer().use { upstream ->
            upstream.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/index")) MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key?signature=secret\"\n#EXTINF:2,\npart.ts?signature=secret\n#EXT-X-ENDLIST")
                else MockResponse().setBody(if (request.path!!.startsWith("/key")) "0123456789abcdef" else "segment") }
            upstream.start()
            MediaCastProxy(context, ExternalMediaEntry("id", "source", "live", upstream.url("/index.m3u8?signature=secret").toString()), loopback, loopback, null).use { proxy ->
                val manifest = client.newCall(Request.Builder().url(proxy.url).build()).execute().use { it.body!!.string() }
                assertTrue(manifest.startsWith("#EXTM3U")); assertFalse(manifest.contains("secret")); assertFalse(manifest.contains(upstream.url("/").toString()))
                val segment = manifest.lineSequence().first { it.startsWith("http:") }
                client.newCall(Request.Builder().url(segment).build()).execute().use { assertEquals("segment", it.body!!.string()) }
                val key = Regex("URI=\"([^\"]+)\"").find(manifest)!!.groupValues[1]
                client.newCall(Request.Builder().url(key).build()).execute().use { assertEquals("0123456789abcdef", it.body!!.string()) }
            }
        }
    }
    @Test fun `empty files return an empty body and invalid ranges return 416`() {
        val file = java.io.File(context.cacheDir, "empty.mp4").apply { writeBytes(byteArrayOf()) }
        MediaCastProxy(context, ExternalMediaEntry("id", "local", "empty", file.toURI().toString()), loopback, loopback, null).use { proxy ->
            client.newCall(Request.Builder().url(proxy.url).build()).execute().use { assertEquals(200, it.code); assertEquals("", it.body!!.string()) }
            client.newCall(Request.Builder().url(proxy.url).header("Range", "bytes=3-1").build()).execute().use { assertEquals(416, it.code) }
        }
    }
    @Test fun `range validation rejects multipart reversed and oversized values`() {
        assertEquals(Triple(0L, null, null), parseCastRange(null))
        for (value in listOf("bytes=9-1", "bytes=0-1,3-5", "bytes=-0", "bytes=-", "bytes=999999999999999999999-")) {
            try { parseCastRange(value); fail(value) } catch (_: IllegalArgumentException) { }
        }
    }
}
