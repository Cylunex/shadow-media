package top.cylunex.shadowmedia.network

import okhttp3.OkHttpClient
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
}
