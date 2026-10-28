package com.shadow.media.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelAutoBindingTest {
    @Test fun retryIncludesIncompleteAssociationsAndMergesReceiptsWithoutLosingOtherSources() {
        val incomplete = JSONObject("""{"provider_id":"a","ok":true,"auto_bind_complete":false}""")
        assertTrue(novelSourceNeedsRetry(incomplete))
        assertTrue(novelSourceNeedsRetry(JSONObject("""{"ok":true,"auto_bind":{"failed":1}}""")))
        assertFalse(novelSourceNeedsRetry(JSONObject("""{"ok":true,"auto_bind":{"conflict":1,"skipped":2}}""")))
        assertFalse(novelSourceNeedsRetry(JSONObject("""{"ok":true}""")))
        val base = JSONObject().put("items", JSONArray(listOf(row(id = "a"), row(id = "b"))))
            .put("sources", JSONArray(listOf(incomplete, JSONObject().put("provider_id", "b").put("ok", true))))
        val retry = JSONObject().put("items", JSONArray(listOf(row(id = "a").put("auto_bind", JSONObject().put("status", "bound")))))
            .put("sources", JSONArray(listOf(JSONObject().put("provider_id", "a").put("ok", true).put("auto_bind_complete", true))))
        val merged = mergeNovelSourceRetry(base, retry)
        assertEquals(2, merged.novelObjects("items").size)
        assertEquals("bound", merged.novelObjects("items").first().getJSONObject("auto_bind").getString("status"))
        assertFalse(merged.novelObjects("sources").any(::novelSourceNeedsRetry))
        assertTrue(base.novelObjects("sources").any(::novelSourceNeedsRetry))
    }
    @Test fun matchingSourcesLeadWithoutDroppingAlternativesOrReorderingEqualMatches() {
        val unrelated = row(author = "另一作者", id = "other")
        val first = row(id = "first"); val second = row(id = "second")
        val unknown = row(author = "", id = "unknown")
        val rows = listOf(unrelated, first, unknown, second)
        assertEquals(listOf(first, second, unrelated, unknown), novelWorkSourceResults(work(), rows))
        assertEquals(listOf(first, second), novelWorkSourceResults(work(), rows, true))
        assertEquals(rows, novelWorkSourceResults(null, rows))
        assertTrue(novelWorkSourceResults(null, rows, true).isEmpty())
    }
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
