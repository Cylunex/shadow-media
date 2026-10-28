package com.shadow.media.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelAutoBindingTest {
    private fun work(author: String = "行舟") = JSONObject().put("id", "work").put("title", "山海来信").put("author", author)
    private fun row(name: String = "山海来信", author: String = "行舟", id: String = "source") = JSONObject()
        .put("name", name).put("author", author).put("provider_id", id).put("bookUrl", "https://example.com/$id")
        .put("capabilities", JSONArray(listOf("detail", "toc", "chapter")))
    @Test fun matchingRequiresBothKnownFieldsAndNeverFuzzyTitles() {
        assertTrue(sameNovelIdentity(work(), row(" 山海来信 ", "行舟　")))
        assertFalse(sameNovelIdentity(work(), row(author = "另一作者")))
        assertFalse(sameNovelIdentity(work(), row(author = "")))
        assertFalse(sameNovelIdentity(work("佚名"), row(author = "佚名")))
        assertFalse(sameNovelIdentity(work(), row(name = "山海来信全集")))
        assertFalse(sameNovelIdentity(JSONObject().put("title", "").put("author", "行舟"), row(name = "")))
    }
    @Test fun automaticAssociationIsIdempotentSkipsMismatchesAndIsolatesFailures() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val body = JSONObject(request.body.readUtf8())
            return if (body.getString("provider_id") == "conflict") MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"already_linked"}}""")
            else MockResponse().setBody("""{"work_id":"work","binding_id":"binding","job_id":"directory"}""")
        } }; server.start()
        try {
            val client = NovelSourceSearch(NovelApi())
            val c = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true)
            val results = listOf(row(author = ""), row(author = "别人"), row(id = "conflict"), row(), row())
            val attempted = mutableSetOf<String>(); val receipts = mutableMapOf<String, JSONObject>(); val failures = mutableMapOf<String, String>()
            repeat(2) { client.autoBindMatches(c, work(), results, NovelSourceFeatures(intakeReuse = true), attempted,
                { k, v -> receipts[k] = v }, { k, v -> failures[k] = v }) }
            assertEquals(1, receipts.size); assertEquals(1, failures.size); assertEquals(2, server.requestCount)
            repeat(2) { val request = server.takeRequest(); assertEquals("/api/v1/source-results/collect", request.path) }
        } finally { server.shutdown() }
    }
}
