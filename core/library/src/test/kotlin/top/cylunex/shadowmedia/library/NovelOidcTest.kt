package top.cylunex.shadowmedia.library

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelOidcTest {
    private fun discovery() = JSONObject().put("issuer", "https://id.example.com").put("client_id", "native-test")
        .put("resource", "https://example.com").put("redirect_uri", NOVEL_OIDC_REDIRECT).put("api_base", "https://example.com/novel/")
    @Test fun `discovery accepts a canonical same-origin prefix but rejects token export`() {
        val base = "https://example.com".toHttpUrl()
        assertEquals("https://example.com/novel/", NovelLoginConfig.parse(base, discovery()).apiBase)
        listOf(discovery().put("api_base", "https://evil.example.com/"),
            discovery().put("issuer", "http://id.example.com"), discovery().put("redirect_uri", "other:/redirect"),
            discovery().put("resource", "https://other.example.com")).forEach {
            try { NovelLoginConfig.parse(base, it); fail("unsafe auth discovery must fail") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun `OIDC retries a rejected access token once and shares fresh credentials with downloads`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"authentication_required\"}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setBody("fixture-publication"))
            val connection = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = server.url("/novel/").toString(), allowHttp = true,
                oidcIssuer = "https://id.example.com", oidcSubject = "owner")
            val refreshes = mutableListOf<Boolean>()
            val api = NovelApi(bearer = NovelBearerTokens { _, force -> refreshes += force; if (force || refreshes.size > 2) "new-token" else "old-token" })
            assertTrue(api.json(connection, listOf("works")).getBoolean("ok"))
            assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))
            val file = java.io.File.createTempFile("novel-oidc-test", ".epub")
            try { api.download(connection, listOf("works", "one", "publication.epub"), file); assertEquals("fixture-publication", file.readText()) }
            finally { file.delete() }
            assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))
            assertEquals(listOf(false, true, false), refreshes)
        } finally { server.shutdown() }
    }
    @Test fun `authentication rejection is bounded and never falls back to Basic`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"authentication_required\"}")) }
            val c = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true,
                username = "legacy-user", password = "legacy-password", oidcIssuer = "https://id.example.com")
            try { NovelApi(bearer = NovelBearerTokens { _, _ -> "rejected" }).json(c, listOf("works")); fail("must require login") }
            catch (error: NovelApiException) { assertEquals(401, error.status) }
            repeat(2) { assertEquals("Bearer rejected", server.takeRequest().getHeader("Authorization")) }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
