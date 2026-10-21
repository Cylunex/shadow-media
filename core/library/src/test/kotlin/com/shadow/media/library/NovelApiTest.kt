package com.shadow.media.library

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelApiTest {
    @Test fun subpathAndRepeatedFiltersAreEncodedWithoutLosingPrefix() {
        val c = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = "https://example.com/novel")
        val query = NovelQuery(q = "书 & 作者", genres = listOf("冒险", "科幻"), excluded = listOf("悲剧"), allGenres = true)
        val url = NovelApi().url(c, listOf("works"), query.parameters())
        assertEquals("/novel/api/v1/works", url.encodedPath)
        assertEquals(listOf("冒险", "科幻"), url.queryParameterValues("genre_tag"))
        assertEquals("all", url.queryParameter("tag_mode")); assertEquals("书 & 作者", url.queryParameter("q"))
    }
    @Test fun crossOriginRedirectNeverSendsCredentialsOrMutation() = runBlocking {
        val origin = MockWebServer(); val other = MockWebServer(); origin.start(); other.start()
        try {
            val c = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = origin.url("/novel/").toString(), token = "REPLACE_TOKEN", allowHttp = true)
            origin.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/steal")))
            other.enqueue(MockResponse().setBody("{}"))
            try { NovelApi().json(c, listOf("me", "progress", "work"), method = "PUT", body = JSONObject().put("chapter_id", "test")); fail("redirect must fail") }
            catch (_: java.io.IOException) {}
            assertEquals("Bearer REPLACE_TOKEN", origin.takeRequest().getHeader("Authorization"))
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { origin.shutdown(); other.shutdown() }
    }
    @Test fun conflictCarriesCurrentPositionWithoutEchoingServerSecrets() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"progress_conflict","message":"secret-server-error","current":{"revision":7,"chapter_id":"c"}}}"""))
            val c = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true)
            try { NovelApi().json(c, listOf("me", "progress", "work")); fail("must conflict") }
            catch (e: NovelApiException) { assertEquals(7, e.detail.getJSONObject("current").getInt("revision")); assertFalse(e.message.orEmpty().contains("secret")) }
        } finally { server.shutdown() }
    }
    @Test fun cacheRemovesUrlsAndPathsRecursively() {
        val clean = novelCacheValue(JSONObject("""{"title":"keep","url":"https://example.com/REPLACE_TOKEN","versions":[{"file_path":"REPLACE_PATH","id":"version"}],"popularity":{"source_ref":"REPLACE_SECRET","source":"source"}}""")) as JSONObject
        assertEquals("keep", clean.getString("title")); assertFalse(clean.toString().contains("REPLACE"))
        assertEquals("version", clean.getJSONArray("versions").getJSONObject(0).getString("id"))
    }
}
