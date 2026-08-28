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
        val source = repository.inspectPayload(
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

        assertEquals(ExternalSourceKind.TVBOX_CONFIG, source.kind)
        assertEquals("My sources", source.name)
        assertEquals(3, source.siteCount)
        assertEquals(1, source.safeSiteCount)
        assertEquals(2, source.runtimeRequiredCount)
        assertEquals(1, source.liveCount)
    }

    @Test
    fun `counts m3u channels as live subscription`() {
        val source = repository.inspectPayload(
            "https://live.example.com/channels.m3u",
            """
                #EXTM3U
                #EXTINF:-1 group-title="News",Channel One
                https://stream.example.com/one.m3u8
                #EXTINF:-1 group-title="News",Channel Two
                https://stream.example.com/two.m3u8
            """.trimIndent(),
        )

        assertEquals(ExternalSourceKind.LIVE_PLAYLIST, source.kind)
        assertEquals(2, source.liveCount)
        assertTrue(source.id.isNotBlank())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown executable style payload`() {
        repository.inspectPayload("https://config.example.com/source", "function init() { return 1 }")
    }
}
