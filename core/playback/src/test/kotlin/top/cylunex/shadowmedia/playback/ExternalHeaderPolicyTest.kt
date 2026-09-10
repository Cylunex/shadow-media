package top.cylunex.shadowmedia.playback

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ExternalHeaderPolicyTest {
    private val origin = "https://example.com/".toHttpUrl()
    private val headers = Headers.headersOf("Authorization", "Bearer test", "Cookie", "test=1", "X-API-Key", "test", "X-MediaBrowser-Token", "test", "X-Emby-Token", "test", "Range", "bytes=100-")
    @Test fun `keeps credentials on exact resource origin`() { assertEquals(headers, externalHeaders(origin, origin.resolve("/video")!!, headers)) }
    @Test fun `drops every supported credential for CDN port change or downgrade`() {
        for (url in listOf("https://cdn.example.com/file", "https://example.com:8443/file", "http://example.com/file")) {
            val scoped = externalHeaders(origin, url.toHttpUrl(), headers)
            assertEquals(setOf("Range"), scoped.names())
            assertEquals("bytes=100-", scoped["Range"])
        }
    }
    @Test fun `missing origin cannot authorize credentials`() { assertEquals(setOf("Range"), externalHeaders(null, origin, headers).names()) }
}
