package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cylunex.shadowmedia.model.ExternalSourceKind

class ExternalSourcesTest {
    private val repository = SafeExternalSourceRepository(OkHttpClient())

    @Test
    fun `inspects tvbox config without executing runtime sites`() {
        val source = repository.importPayload(
            "https://config.example.com/tv.json",
            """
                {
                  "name": "My sources",
                  "sites": [
                    {"key":"remote","api":"https://api.example.com/provide/vod"},
                    {"key":"jar","api":"csp_Custom"},
                    {"key":"js","api":"./quickjs/site.js"}
                  ],
                  // TVBox 配置常见行注释，不能误删字符串里的 https://
                  "lives": [{"name":"Home live","url":"https://example.com/live.m3u"}]
                }
            """.trimIndent(),
        )

        assertEquals(ExternalSourceKind.TVBOX_CONFIG, source.summary.kind)
        assertEquals("My sources", source.summary.name)
        assertEquals(3, source.summary.siteCount)
        assertEquals(1, source.summary.safeSiteCount)
        assertEquals(2, source.summary.runtimeRequiredCount)
        assertEquals(1, source.summary.liveCount)
        assertTrue(repository.entries(source).isEmpty())
        assertEquals("https://api.example.com/provide/vod", repository.catalogSites(source).single().apiUrl)
    }

    @Test
    fun `expands tvbox catalog and nested live playlist without executing sites`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (contentType, body) = when (chain.request().url.encodedPath) {
                "/catalog.json" -> "text/html" to """
                    {
                      "urls": [{"name":"线路一","url":"https://source.example/child.json"}]
                      // compatibility comment
                    }
                """.trimIndent()
                "/child.json" -> "application/json" to """
                    {
                      "sites": [
                        {"key":"remote","name":"公开接口","api":"https://api.example.com/provide/vod"},
                        {"key":"jar","api":"csp_Custom"}
                      ],
                      "lives": [{"name":"直播一","url":"/live.txt","ua":"Declared UA"}]
                    }
                """.trimIndent()
                "/live.txt" -> "text/plain" to """
                    News,#genre#
                    Channel One,https://video.example/one.m3u8
                """.trimIndent()
                else -> error("Unexpected request ${chain.request().url}")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }.build()
        val imported = SafeExternalSourceRepository(client)
            .importFromUrl("https://source.example/catalog.json", allowInsecureHttp = false)

        assertEquals(ExternalSourceKind.DECLARATIVE, imported.summary.kind)
        assertEquals(1, imported.summary.siteCount)
        assertEquals(1, imported.resolvedEntries.size)
        assertEquals("线路一 · 直播一 · News", imported.resolvedEntries.single().group)
        assertEquals("Declared UA", imported.resolvedEntries.single().requestHeaders["User-Agent"])
        assertEquals(1, imported.resolvedCatalogSites.size)
        assertEquals("线路一 · 公开接口", imported.resolvedCatalogSites.single().name)
    }

    @Test
    fun `counts m3u channels as live subscription`() {
        val source = repository.importPayload(
            "https://live.example.com/channels.m3u",
            """
                #EXTM3U
                #EXTINF:-1 tvg-logo="/one.png" group-title="News",Channel One
                one.m3u8|User-Agent=Shadow%20Test&Referer=https%3A%2F%2Fexample.com
                #EXTINF:-1 group-title="News",Channel Two
                https://stream.example.com/two.m3u8
            """.trimIndent(),
        )

        val entries = repository.entries(source)
        assertEquals(ExternalSourceKind.LIVE_PLAYLIST, source.summary.kind)
        assertEquals(2, source.summary.liveCount)
        assertEquals("Channel One", entries.first().title)
        assertEquals("News", entries.first().group)
        assertEquals("https://live.example.com/one.m3u8", entries.first().url)
        assertEquals("https://live.example.com/one.png", entries.first().logoUrl)
        assertEquals("Shadow Test", entries.first().requestHeaders["User-Agent"])
        assertTrue(source.summary.id.isNotBlank())
    }

    @Test
    fun `parses grouped tvbox txt entries`() {
        val source = repository.importPayload(
            "content://documents/video-source.txt",
            """
                电影,#genre#
                测试电影,https://video.example.com/movie.mp4
                直播,#genre#
                测试频道,https://video.example.com/live.m3u8
            """.trimIndent(),
            "我的视频源.txt",
        )

        val entries = repository.entries(source)
        assertEquals("我的视频源.txt", source.summary.name)
        assertEquals(2, entries.size)
        assertEquals("电影", entries[0].group)
        assertEquals("测试电影", entries[0].title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown executable style payload`() {
        repository.importPayload("https://config.example.com/source", "function init() { return 1 }")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects encrypted runtime payload`() {
        repository.importPayload("https://config.example.com/encrypted", "ab12".repeat(200))
    }
}
