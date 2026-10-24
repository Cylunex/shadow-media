package com.shadow.media.library

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelIntakeContractTest {
    private val features = NovelSourceFeatures(true, true, true)
    private fun row() = JSONObject("""{"provider_id":"s","bookUrl":"https://example.com/book/1","name":"山海来信","author":"行舟","intro":"简介","capabilities":["search","detail","toc","chapter"]}""")
    private fun connection(s: MockWebServer) = CatalogConnection(name = "测试", kind = CatalogKind.OPDS, url = s.url("/novel/").toString(), allowHttp = true)
    private fun server(block: suspend (MockWebServer, NovelSourceSearch, CatalogConnection) -> Unit) = runBlocking {
        val s = MockWebServer(); s.start()
        try { block(s, NovelSourceSearch(NovelApi()), connection(s)) } finally { s.shutdown() }
    }
    @Test fun searchModesKeepFiveThousandSourceScopeWithoutIncreasingBatchSize() {
        val ids = (1..5000).map { "source-$it" }
        val modern = NovelSearchOptions(precise = true, providerIds = ids, groups = listOf("a", "b"), mode = "all").body("山海")
        assertEquals(5000, modern.getJSONArray("provider_ids").length()); assertEquals(12, modern.getInt("limit"))
        assertEquals("all", modern.getString("mode")); assertTrue(modern.getBoolean("precise"))
        assertEquals(2, modern.getJSONArray("group_ids").length())
        assertFalse(NovelSearchOptions(providerIds = listOf("s")).body("山海").has("mode"))
        for (options in listOf(NovelSearchOptions(providerIds = ids), NovelSearchOptions(providerIds = ids + "extra", mode = "all"), NovelSearchOptions(mode = "other"))) {
            try { options.body("山海"); fail("invalid scope") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun capabilitiesUseExplicitFlagsAndDoNotHideAuthenticationFailure() = server { s, api, c ->
        s.enqueue(MockResponse().setBody("""{"capabilities":{"source_search_modes":true,"source_chapter_open":true,"source_intake_reuse":true}}"""))
        s.enqueue(MockResponse().setBody("""{"capabilities":{"source_search":true}}"""))
        s.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        s.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals(features, api.features(c)); assertEquals(NovelSourceFeatures(), api.features(c)); assertEquals(NovelSourceFeatures(), api.features(c))
        try { api.features(c); fail("auth error") } catch (e: NovelApiException) { assertEquals(401, e.status) }
    }
    @Test fun selectedWorkUsesUnifiedCollectionWhileLegacyServiceUsesExistingBinding() = server { s, api, c ->
        repeat(2) { s.enqueue(MockResponse().setBody("""{"work_id":"existing","binding_id":"binding","job_id":"toc"}""")) }
        api.collect(c, row(), workId = "existing", features = features)
        var request = s.takeRequest(); assertEquals("/novel/api/v1/source-results/collect", request.path)
        var body = JSONObject(request.body.readUtf8()); assertEquals("existing", body.getString("work_id")); assertFalse(body.has("create_new"))
        api.collect(c, row(), workId = "existing")
        request = s.takeRequest(); assertEquals("/novel/api/v1/source-bindings", request.path)
        body = JSONObject(request.body.readUtf8()); assertEquals("existing", body.getString("work_id")); assertFalse(body.has("create_new"))
        assertEquals(2, s.requestCount) // Caller retains the receipt before any directory wait.
        try { api.collect(c, row().put("capabilities", org.json.JSONArray(listOf("search"))), workId = "existing"); fail("legacy metadata association") }
        catch (_: IllegalArgumentException) {}
        assertEquals(2, s.requestCount)
    }
    @Test fun ambiguousIntakePreservesCandidatesUntilExplicitSelectionOrSeparateCreation() = server { s, api, c ->
        s.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"choose_existing_work","candidates":[{"id":"a","title":"山海来信","author":"行舟"},{"id":"b","title":"山海来信","author":"行舟"}]}}"""))
        try { api.collect(c, row(), features = features); fail("identity required") }
        catch (e: NovelApiException) { assertEquals("choose_existing_work", e.code); assertEquals(2, e.detail.novelObjects("candidates").size) }
        assertEquals(1, s.requestCount); assertFalse(JSONObject(s.takeRequest().body.readUtf8()).has("create_new"))
        s.enqueue(MockResponse().setBody("""{"work_id":"a"}"""))
        api.collect(c, row(), workId = "a", features = features)
        assertEquals("a", JSONObject(s.takeRequest().body.readUtf8()).getString("work_id"))
        s.enqueue(MockResponse().setBody("""{"work_id":"new"}"""))
        api.collect(c, row(), createNew = true, features = features)
        val explicit = JSONObject(s.takeRequest().body.readUtf8()); assertTrue(explicit.getBoolean("create_new")); assertFalse(explicit.has("work_id"))
    }
    @Test fun alreadyLinkedConflictNeverRetriesWithNewIdentity() = server { s, api, c ->
        s.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"already_linked","work_id":"other"}}"""))
        try { api.collect(c, row(), workId = "existing", features = features); fail("must not steal source") }
        catch (e: NovelApiException) { assertEquals("already_linked", e.code) }
        assertEquals(1, s.requestCount)
    }
    @Test fun previewChapterUsesExactLocatorInDirectoryJobVersionWithoutFetchingTextOrWritingProgress() = server { s, api, c ->
        val preview = JSONObject("""{"toc":{"items":[{"title":"同名章节","chapter_url":"https://example.com/first"},{"title":"同名章节","chapter_url":"https://example.com/second?part=2"}]}}""")
        val chapter = novelPreviewChapters(preview, "同名", true).first()
        s.enqueue(MockResponse().setBody("""{"work_id":"work","binding_id":"binding","job_id":"toc"}"""))
        s.enqueue(MockResponse().setBody("""{"state":"succeeded","result":{"version_id":"fixed-version"}}"""))
        s.enqueue(MockResponse().setBody("""{"work_id":"work","version_id":"fixed-version","chapter_id":"chapter-2","url":"/novel/reader/work?chapter=chapter-2"}"""))
        val receipt = api.collect(c, row(), features = features)
        val version = api.collectionVersion(c, receipt)
        val opened = api.openChapter(c, receipt, version, chapter.getString("chapter_url"))
        assertEquals(NovelSourceOpening("work", "fixed-version", "chapter-2"), opened)
        assertEquals("/novel/api/v1/source-results/collect", s.takeRequest().path)
        assertEquals("/novel/api/v1/jobs/toc", s.takeRequest().path)
        val request = s.takeRequest(); assertEquals("/novel/api/v1/source-results/open-chapter", request.path)
        val body = JSONObject(request.body.readUtf8()); assertEquals("binding", body.getString("binding_id"))
        assertEquals("fixed-version", body.getString("version_id")); assertEquals("https://example.com/second?part=2", body.getString("chapter_url"))
        assertEquals(3, s.requestCount)
    }
    @Test fun missingChapterAndMismatchedIdentityCannotFallBackToTitleOrOrdinal() = server { s, api, c ->
        val receipt = JSONObject("""{"work_id":"work","binding_id":"binding"}""")
        s.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":{"message":"章节不在版本中"}}"""))
        try { api.openChapter(c, receipt, "v", "https://example.com/c"); fail("missing chapter") } catch (e: NovelApiException) { assertEquals(404, e.status) }
        s.enqueue(MockResponse().setBody("""{"work_id":"other","version_id":"v","chapter_id":"chapter"}"""))
        s.enqueue(MockResponse().setBody("""{"id":"work"}"""))
        try { api.openChapter(c, receipt, "v", "https://example.com/c"); fail("wrong identity") } catch (_: IllegalArgumentException) {}
        assertEquals(3, s.requestCount)
    }
    @Test fun redirectedExistingWorkKeepsItsIdentityDuringSourceIntakeAndChapterOpening() = server { s, api, c ->
        s.enqueue(MockResponse().setBody("""{"work_id":"canonical","binding_id":"binding","job_id":"toc"}"""))
        s.enqueue(MockResponse().setBody("""{"id":"canonical"}"""))
        val receipt = api.collect(c, row(), "old-work", features = NovelSourceFeatures(intakeReuse = true))
        assertEquals("canonical", receipt.getString("work_id"))
        val first = s.takeRequest()
        assertEquals("old-work", JSONObject(first.body.readUtf8()).getString("work_id"))
        assertEquals("/novel/api/v1/works/old-work", s.takeRequest().path)
        s.enqueue(MockResponse().setBody("""{"work_id":"canonical","version_id":"v","chapter_id":"chapter"}"""))
        s.enqueue(MockResponse().setBody("""{"id":"canonical"}"""))
        assertEquals("canonical", api.openChapter(c, JSONObject().put("work_id", "old-work").put("binding_id", "binding"), "v", "https://example.com/c").workId)
        assertEquals(4, s.requestCount)
    }
    @Test fun resumePollsOriginalJobAndRetainsCancelledPartialResultWithoutNewSearch() = server { s, api, c ->
        s.enqueue(MockResponse().setBody("""{"state":"cancelled","result":{"items":[{"name":"已返回"}],"remaining":27,"truncated":true}}"""))
        var last: JSONObject? = null
        try { api.resumeSearch(c, "original") { last = it }; fail("cancelled") } catch (_: IllegalStateException) {}
        assertEquals("已返回", last!!.getJSONObject("result").novelObjects("items").single().getString("name"))
        assertEquals("/novel/api/v1/jobs/original", s.takeRequest().path); assertEquals(1, s.requestCount)
    }
}
