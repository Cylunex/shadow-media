package com.shadow.media.library

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelSourceSearchTest {
    private fun connection(server: MockWebServer) = CatalogConnection(name = "test", kind = CatalogKind.OPDS,
        url = server.url("/novel/").toString(), token = "REPLACE_TOKEN", allowHttp = true)
    private fun readableRow() = JSONObject("""{"provider_id":"source-a","bookUrl":"https://example.com/book/1","capabilities":["search","detail","toc","chapter"]}""")

    @Test fun titleSearchPostsKeywordAndRetainsPartialResultsBeforeCompletion() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"job_id":"search-1"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"running","result":{"items":[{"name":"原创作品"}],"sources":[{"name":"来源甲","ok":true,"count":1}]}}"""))
            server.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{"items":[{"name":"原创作品"},{"name":"另一版本"}]}}"""))
            val updates = mutableListOf<JSONObject>()
            val completed = NovelSourceSearch(NovelApi()).search(connection(server), " 原创作品 ") { updates += it }
            assertEquals(listOf("running", "succeeded"), updates.map { it.getString("state") })
            assertEquals(1, updates.first().getJSONObject("result").novelObjects("items").size)
            assertEquals(2, completed.getJSONObject("result").novelObjects("items").size)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/novel/api/v1/search-jobs", request.path)
            assertEquals("原创作品", JSONObject(request.body.readUtf8()).getString("q"))
            assertEquals("Bearer REPLACE_TOKEN", request.getHeader("Authorization"))
            repeat(2) { assertEquals("/novel/api/v1/jobs/search-1", server.takeRequest().path) }
        } finally { server.shutdown() }
    }

    @Test fun readableResultBindsToTheExistingWorkAndWaitsForItsDirectory() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"work_id":"existing-work","binding_id":"binding-1","job_id":"toc-1"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{"version_id":"version-1"}}"""))
            val binding = NovelSourceSearch(NovelApi()).bind(connection(server), "existing-work", readableRow())
            assertEquals("binding-1", binding.getString("binding_id"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/novel/api/v1/source-bindings", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("existing-work", body.getString("work_id"))
            assertEquals("source-a", body.getString("provider_id"))
            assertEquals("https://example.com/book/1", body.getString("url"))
            assertEquals("/novel/api/v1/jobs/toc-1", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun metadataOnlySourcesAndBlankKeywordsNeverIssueRequests() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = NovelSourceSearch(NovelApi())
            val row = JSONObject("""{"capabilities":["search","detail"]}""")
            assertFalse(readableNovelSource(row))
            try { client.bind(connection(server), "work", row); fail("metadata cannot be read") }
            catch (_: IllegalArgumentException) {}
            try { client.search(connection(server), " ") {}; fail("blank keyword") }
            catch (_: IllegalArgumentException) {}
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun failedSearchDeliversItsPartialResultsAndDoesNotKeepPolling() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"job_id":"search-1"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"failed","error":"来源暂不可用","result":{"items":[{"name":"已返回作品"}]}}"""))
            var latest: JSONObject? = null
            try { NovelSourceSearch(NovelApi()).search(connection(server), "原创作品") { latest = it }; fail("failed job") }
            catch (e: IllegalStateException) { assertEquals("来源暂不可用", e.message) }
            assertEquals("已返回作品", latest!!.getJSONObject("result").novelObjects("items").single().getString("name"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun dismissingSearchCancelsPollingWithoutLaunchingAnotherRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"job_id":"search-1"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"running"}"""))
            val updated = CompletableDeferred<Unit>()
            val search = launch { NovelSourceSearch(NovelApi()).search(connection(server), "原创作品") { updated.complete(Unit) } }
            withTimeout(5_000) { updated.await() }
            search.cancelAndJoin()
            assertTrue(search.isCancelled)
            repeat(2) { assertNotNull(server.takeRequest(100, TimeUnit.MILLISECONDS)) }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }

    @Test fun bindingConflictsDoNotCreateANewWorkOrFetchADirectory() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"already_linked"}}"""))
            try { NovelSourceSearch(NovelApi()).bind(connection(server), "existing-work", readableRow()); fail("identity conflict") }
            catch (e: NovelApiException) { assertEquals(409, e.status) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
