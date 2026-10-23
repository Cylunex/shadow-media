package com.shadow.media.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelToolsContractTest {
    private fun connection(s: MockWebServer, root: String = "/novel/") = CatalogConnection(name = "测试书库", kind = CatalogKind.OPDS,
        url = s.url(root).toString(), token = "REPLACE_TOKEN", allowHttp = true)
    private fun row() = JSONObject("""{"provider_id":"source","bookUrl":"https://example.com/book/1","name":"山海来信","author":"行舟","intro":"列表简介","capabilities":["search","detail","toc","chapter"]}""")
    private fun server(block: suspend (MockWebServer) -> Unit) = runBlocking {
        val s = MockWebServer(); s.start()
        try { block(s) } finally { s.shutdown() }
    }

    @Test fun searchKeepsPreciseGroupsProviderAndPageOnSingleSourcePagination() = server { s ->
        s.enqueue(MockResponse().setBody("""{"job_id":"job"}""")); s.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{"items":[],"sources":[{"ok":true,"page":2,"raw_count":12,"count":0}]}}"""))
        val result = NovelSourceSearch(NovelApi()).search(connection(s), " 星河 ", NovelSearchOptions(true, listOf("a", "b"), listOf("source"), page = 2)) {}
        val req = s.takeRequest(); val body = JSONObject(req.body.readUtf8())
        assertTrue(body.getBoolean("precise")); assertEquals(listOf("a", "b"), body.novelStrings("group_ids"))
        assertEquals(2, body.getInt("page")); assertEquals(listOf("source"), body.novelStrings("provider_ids"))
        assertEquals("星河", body.getString("q"))
        assertEquals(3, novelSourceNextPage(result.getJSONObject("result").novelObjects("sources").single(), true))
    }
    @Test fun paginationDoesNotConfuseEmptyMatchesWithEndOrFailedPage() {
        assertEquals(2, novelSourceNextPage(JSONObject("""{"ok":true,"page":1,"raw_count":5,"count":0}"""), true))
        assertNull(novelSourceNextPage(JSONObject("""{"ok":false,"page":1,"raw_count":5}"""), true))
        assertNull(novelSourceNextPage(JSONObject("""{"ok":true,"page":100,"raw_count":5}"""), true))
        assertNull(novelSourceNextPage(JSONObject("""{"ok":true,"page":1,"raw_count":5}"""), false))
    }
    @Test fun sourceFilterUsesRepeatedAndTagsOrGroupsAndIndependentExploreFlag() = server { s ->
        s.enqueue(MockResponse().setBody("""{"items":[],"total":0}""")); s.enqueue(MockResponse().setBody("""{"provider_ids":["a","b"],"total":2}"""))
        val filter = NovelSourceFilter(groups = listOf("组甲", "组乙"), tags = listOf("标签甲", "标签乙"), exploreEnabled = "false", groupMode = "ungrouped", sort = "latency")
        val tools = NovelLibraryTools(NovelApi()); tools.catalog(connection(s), filter, 40)
        val url = s.takeRequest().requestUrl!!
        assertEquals(listOf("组甲", "组乙"), url.queryParameterValues("group_id")); assertEquals(listOf("标签甲", "标签乙"), url.queryParameterValues("tag"))
        assertEquals("false", url.queryParameter("explore_enabled")); assertEquals("40", url.queryParameter("offset")); assertEquals("latency", url.queryParameter("sort"))
        assertEquals(listOf("a", "b"), tools.selection(connection(s), filter))
        assertNull(s.takeRequest().requestUrl!!.queryParameter("sort"))
    }
    @Test fun metadataPreviewWithoutJobNeverCollectsOrWritesProgress() = server { s ->
        s.enqueue(MockResponse().setBody("""{"job_id":null,"source":{"capabilities":["search"]},"work_id":"canonical","message":"列表信息"}"""))
        val result = NovelSourceSearch(NovelApi()).preview(connection(s), row())
        assertEquals("canonical", result.getString("work_id")); assertEquals(1, s.requestCount)
        assertFalse(readableNovelSource(novelSourceDetail(row(), result)))
        val req = s.takeRequest(); assertEquals("/novel/api/v1/source-previews", req.path)
        assertFalse(JSONObject(req.body.readUtf8()).getBoolean("include_toc"))
    }
    @Test fun tocPreviewKeepsCanonicalFieldsAndPartialStateAndDoesNotFetchBody() = server { s ->
        s.enqueue(MockResponse().setBody("""{"job_id":"preview"}""")); s.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{"detail":{"name":"新书名","word_count":"十万字","latest_chapter":"归来","source_updated_at":"今天","status":"连载"},"toc":{"items":[{"title":"初见"},{"title":"归来"}],"complete":false,"pages":3}}}"""))
        val preview = NovelSourceSearch(NovelApi()).preview(connection(s, "/"), row(), true)
        val detail = novelSourceDetail(row(), preview)
        assertEquals("新书名", detail.getString("name")); assertEquals("行舟", detail.getString("author")); assertEquals("十万字", detail.getString("word_count"))
        assertEquals(listOf("归来", "初见"), novelPreviewTitles(preview, "", true)); assertEquals(listOf("归来"), novelPreviewTitles(preview, "归", false))
        assertFalse(preview.getJSONObject("toc").getBoolean("complete"))
        assertTrue(JSONObject(s.takeRequest().body.readUtf8()).getBoolean("include_toc"))
        assertEquals("/api/v1/jobs/preview", s.takeRequest().path); assertEquals(2, s.requestCount)
    }
    @Test fun failedPreviewRefreshPreservesGoodSynopsisAndToc() {
        val previous = JSONObject("""{"detail":{"intro":"详情简介","word_count":"十万"},"toc":{"items":[{"title":"一"}],"complete":false}}""")
        val next = mergeNovelPreview(previous, JSONObject("""{"detail":{"intro":""},"toc_error":"目录超时"}"""))
        assertEquals("详情简介", novelSourceDetail(row(), next).getString("intro"))
        assertEquals("目录超时", next.getString("toc_error")); assertEquals(1, next.getJSONObject("toc").novelObjects("items").size)
    }
    @Test fun collectionUsesUnifiedEndpointMetadataAndReturnsCanonicalReceiptBeforeToc() = server { s ->
        repeat(2) { s.enqueue(MockResponse().setBody("""{"work_id":"canonical","job_id":"toc"}""")) }
        val search = NovelSourceSearch(NovelApi()); val detail = novelSourceDetail(row(), JSONObject("""{"detail":{"name":"详情标题","intro":"详情简介"}}"""))
        repeat(2) {
            assertEquals("canonical", search.collect(connection(s), detail).getString("work_id"))
            val req = s.takeRequest(); assertEquals("/novel/api/v1/source-results/collect", req.path)
            val body = JSONObject(req.body.readUtf8()); assertEquals("详情标题", body.getString("title")); assertEquals("详情简介", body.getString("intro")); assertFalse(body.has("work_id"))
        }
        assertEquals(2, s.requestCount)
    }
    @Test fun cancelIsExplicitAndUsesSameOriginJobRoute() = server { s ->
        s.enqueue(MockResponse().setBody("""{"state":"running","cancel_requested":true}"""))
        NovelSourceSearch(NovelApi()).cancel(connection(s), "job")
        val req = s.takeRequest(); assertEquals("/novel/api/v1/jobs/job/cancel", req.path); assertEquals("POST", req.method)
    }
    @Test fun rawFileUploadStreamsBytesWithFilenameAndBearer() = server { s ->
        s.enqueue(MockResponse().setBody("""{"work_id":"new-work"}"""))
        val data = "原创章节\n".repeat(10000).toByteArray(); var opened = 0
        val result = NovelLibraryTools(NovelApi()).upload(connection(s), "山海 & 星河.txt") { opened++; ByteArrayInputStream(data) }
        assertEquals("new-work", result.getString("work_id")); assertEquals(1, opened)
        val req = s.takeRequest(); assertArrayEquals(data, req.body.readByteArray()); assertEquals("application/octet-stream", req.getHeader("Content-Type"))
        assertEquals("山海 & 星河.txt", req.requestUrl!!.queryParameter("filename")); assertEquals("Bearer REPLACE_TOKEN", req.getHeader("Authorization"))
    }
    @Test fun rawUploadReopensDocumentForOidcRefresh() = server { s ->
        s.enqueue(MockResponse().setResponseCode(401).setBody("{}")); s.enqueue(MockResponse().setBody("{}"))
        val tokens = mutableListOf<Boolean>(); var opened = 0
        val api = NovelApi(bearer = NovelBearerTokens { _, force -> tokens += force; if (force) "NEW" else "OLD" })
        api.upload(connection(s).copy(oidcIssuer = "https://example.com"), "book.txt") { opened++; ByteArrayInputStream("正文".toByteArray()) }
        assertEquals(listOf(false, true), tokens); assertEquals(2, opened)
        assertEquals("Bearer OLD", s.takeRequest().getHeader("Authorization")); assertEquals("Bearer NEW", s.takeRequest().getHeader("Authorization"))
    }
    @Test fun exportsAcceptArraysAndRejectCrossOriginBeforeOpeningDestination() = server { s ->
        val other = MockWebServer(); other.start()
        try {
            val api = NovelApi(); val out = ByteArrayOutputStream()
            s.enqueue(MockResponse().setBody("""[{"schema":"recipe/v2","name":"原创源"}]"""))
            api.export(connection(s), listOf("providers", "export"), { out }, "POST", JSONObject().put("provider_ids", org.json.JSONArray().put("a")))
            assertTrue(out.toString("UTF-8").startsWith("[")); s.takeRequest()
            s.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/capture")))
            var opened = false
            try { api.export(connection(s), listOf("library", "books", "export"), { opened = true; ByteArrayOutputStream() }); fail() } catch (_: java.io.IOException) {}
            assertFalse(opened); assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { other.shutdown() }
    }
    @Test fun opmlExportEscapesFeedNamesAndUrlsAndOmitsSourcePackages() = server { s ->
        s.enqueue(MockResponse().setBody("""{"items":[{"kind":"rss","name":"山 & 海","url":"https://example.com/rss?a=1&b=2"},{"kind":"sources","name":"规则包","url":"https://example.com/sources"}]}"""))
        val out = ByteArrayOutputStream(); NovelLibraryTools(NovelApi()).exportOpml(connection(s), { out })
        val xml = out.toString("UTF-8"); assertTrue(xml.contains("山 &amp; 海")); assertTrue(xml.contains("a=1&amp;b=2")); assertFalse(xml.contains("规则包"))
    }
    @Test fun libraryImportAndBulkRespectSchemaContentAndSelection() = server { s ->
        s.enqueue(MockResponse().setBody("""{"created":0,"existing_preserved":1}""")); s.enqueue(MockResponse().setBody("""{"changed":2}"""))
        val tools = NovelLibraryTools(NovelApi()); val content = """{"schema":"shadow.library-list/v1","works":[],"lists":[]}"""
        tools.import(connection(s), listOf("library", "books", "import")) { ByteArrayInputStream(content.toByteArray()) }
        assertEquals(content, JSONObject(s.takeRequest().body.readUtf8()).getString("content"))
        tools.bulkBooks(connection(s), listOf("a", "b"), "status", "read")
        val body = JSONObject(s.takeRequest().body.readUtf8()); assertEquals(listOf("a", "b"), body.novelStrings("work_ids")); assertEquals("read", body.getString("value"))
        try { tools.bulkBooks(connection(s), (0..500).map(Int::toString), "shelf_remove"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(2, s.requestCount)
    }
    @Test fun preciseLibraryQueryIsSharedWithNavigationAndCanBeCleared() {
        assertTrue(NovelQuery(precise = true).parameters().contains("precise" to "true"))
        assertFalse(NovelQuery().parameters().any { it.first == "precise" })
    }
}
