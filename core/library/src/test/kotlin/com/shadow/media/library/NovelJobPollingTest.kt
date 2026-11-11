package com.shadow.media.library

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NovelJobPollingTest {
    private fun connection(s: MockWebServer) = CatalogConnection(name = "test", kind = CatalogKind.OPDS,
        url = s.url("/novel/").toString(), token = "REPLACE_TOKEN", allowHttp = true)

    @Test fun conditionalPollRetainsPartialResultsAndBacksOffWithoutUiUpdates() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            s.enqueue(MockResponse().setBody("""{"id":"job","state":"running","result":{"items":[{"name":"已找到作品"}]}}""").addHeader("ETag", "a"))
            repeat(3) { s.enqueue(MockResponse().setResponseCode(304)) }
            s.enqueue(MockResponse().setBody("""{"id":"job","state":"succeeded","result":{"items":[{"name":"完整结果"}]}}""").addHeader("ETag", "b"))
            val delays = mutableListOf<Long>(); val updates = mutableListOf<JSONObject>()
            val result = NovelJobPoller(NovelApi()) { delays += it }.await(connection(s), "job", onUpdate = { updates += it })
            assertEquals(listOf(1000L, 2000L, 4000L, 5000L), delays)
            assertEquals(listOf("running", "succeeded"), updates.map { it.getString("state") })
            assertEquals("已找到作品", updates[0].getJSONObject("result").novelObjects("items")[0].getString("name"))
            assertEquals("完整结果", result.getJSONObject("result").novelObjects("items")[0].getString("name"))
            repeat(5) { index ->
                val request = s.takeRequest()
                assertEquals("false", request.requestUrl!!.queryParameter("include_payload"))
                assertEquals(if (index == 0) null else "a", request.getHeader("If-None-Match"))
                assertEquals("Bearer REPLACE_TOKEN", request.getHeader("Authorization"))
            }
        } finally { s.shutdown() }
    }

    @Test fun olderServerWithoutEtagStillPollsAndDetectsResultChanges() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            repeat(2) { s.enqueue(MockResponse().setBody("""{"state":"running","result":{"items":[]}}""")) }
            s.enqueue(MockResponse().setBody("""{"state":"running","result":{"items":[{"name":"新结果"}]}}"""))
            s.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{}}"""))
            val delays = mutableListOf<Long>(); val updates = mutableListOf<JSONObject>()
            NovelJobPoller(NovelApi()) { delays += it }.await(connection(s), "job", onUpdate = { updates += it })
            assertEquals(listOf(1000L, 2000L, 1000L), delays)
            assertEquals(3, updates.size)
            repeat(4) { assertNull(s.takeRequest().getHeader("If-None-Match")) }
        } finally { s.shutdown() }
    }

    @Test fun previewTimeoutResumesOriginalTaskWithoutAnotherPost() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            s.enqueue(MockResponse().setBody("""{"job_id":"preview"}"""))
            s.enqueue(MockResponse().setBody("""{"state":"running","result":{}}"""))
            s.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{"detail":{"name":"原创作品"}}}"""))
            val api = NovelSourceSearch(NovelApi(), waitMillis = 300)
            val c = connection(s); val row = JSONObject("""{"provider_id":"source","bookUrl":"https://example.com/book/1"}""")
            var id: String? = null
            try { api.preview(c, row, onJob = { id = it }); fail("Must yield pending state") }
            catch (e: NovelJobPendingException) { assertEquals("preview", e.jobId); assertEquals("running", e.latest!!.getString("state")) }
            assertEquals("preview", id)
            val result = api.preview(c, row, previousJob = id, onJob = { id = it })
            assertNull(id); assertEquals("原创作品", result.getJSONObject("detail").getString("name"))
            assertEquals("POST", s.takeRequest().method)
            repeat(2) { assertEquals("GET", s.takeRequest().method) }
            assertEquals(3, s.requestCount)
        } finally { s.shutdown() }
    }

    @Test fun parentCancellationIsNotConvertedIntoPendingOrServerCancellation() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            s.enqueue(MockResponse().setBody("""{"state":"running"}"""))
            val updated = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val pending = launch {
                NovelJobPoller(NovelApi()) { release.await() }.await(connection(s), "job") { updated.complete(Unit) }
            }
            withTimeout(5000) { updated.await() }; pending.cancelAndJoin()
            assertTrue(pending.isCancelled); assertEquals(1, s.requestCount)
            assertEquals("GET", s.takeRequest().method); assertNull(s.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { s.shutdown() }
    }

    @Test fun conditionalStateCannotCrossConnectionOrJob() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            s.enqueue(MockResponse().setBody("""{"state":"running"}""").addHeader("ETag", "a"))
            repeat(2) { s.enqueue(MockResponse().setBody("""{"state":"succeeded"}""")) }
            val api = NovelApi(); val c = connection(s)
            val snapshot = api.conditionalJson(c, listOf("jobs", "one"), emptyList(), null)
            api.conditionalJson(c, listOf("jobs", "two"), emptyList(), snapshot)
            api.conditionalJson(c.copy(token = "REPLACE_OTHER_TOKEN"), listOf("jobs", "one"), emptyList(), snapshot)
            repeat(3) { assertNull(s.takeRequest().getHeader("If-None-Match")) }
        } finally { s.shutdown() }
    }

    @Test fun invalid304OrWrongJobDoesNotBecomeSuccessfulState() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            s.enqueue(MockResponse().setResponseCode(304))
            val api = NovelApi(); val c = connection(s)
            try { NovelJobPoller(api).await(c, "expected") {}; fail("No retained response") }
            catch (e: NovelApiException) { assertEquals(304, e.status) }
            s.enqueue(MockResponse().setBody("""{"id":"other","state":"succeeded"}"""))
            try { NovelJobPoller(api).await(c, "expected") {}; fail("Wrong task") }
            catch (_: IllegalArgumentException) {}
        } finally { s.shutdown() }
    }

    @Test fun sourceChoicesReuseScopeRespectFiltersAndInvalidateExplicitly() = runBlocking {
        val s = MockWebServer(); s.start()
        try {
            val browser = """{"items":[{"id":"a","name":"甲"},{"id":"b","name":"乙"}]}"""
            s.enqueue(MockResponse().setBody(browser)); s.enqueue(MockResponse().setBody(browser))
            s.enqueue(MockResponse().setBody("""{"provider_ids":["b"]}"""))
            s.enqueue(MockResponse().setBody(browser)); s.enqueue(MockResponse().setBody(browser))
            val api = NovelSourceSearch(NovelApi()); val c = connection(s)
            val filter = NovelSourceFilter(enabled = "true", capability = "search", groups = listOf("g"))
            api.choices(c, filter)[0].put("name", "caller edit")
            assertEquals("甲", api.choices(c, filter)[0].getString("name")); assertEquals(1, s.requestCount)
            assertEquals(listOf("b"), api.choices(c, filter.copy(tags = listOf("常用"))).map { it.getString("id") })
            api.choices(c, filter, refresh = true); api.invalidateChoices(); api.choices(c, filter)
            assertEquals(5, s.requestCount)
            assertEquals(listOf("g"), s.takeRequest().requestUrl!!.queryParameterValues("group_id"))
            s.takeRequest(); assertEquals(listOf("常用"), s.takeRequest().requestUrl!!.queryParameterValues("tag"))
        } finally { s.shutdown() }
    }
}
