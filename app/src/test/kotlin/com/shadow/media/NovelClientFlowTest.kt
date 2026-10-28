package com.shadow.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import com.shadow.media.ui.theme.ShadowMediaTheme
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelClientFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }
    private val row = """{"provider_id":"s","provider_name":"原创来源","bookUrl":"https://example.com/book/1","name":"山海来信","author":"行舟","intro":"沿着来信寻找远方灯火。","capabilities":["search","detail","toc","chapter"]}"""
    @Before fun start() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request.path.orEmpty() to request.body.clone().readUtf8()
            return handler(request)
        } }; server.start()
    }
    @After fun stop() { server.shutdown() }
    private fun json(value: String) = MockResponse().setBody(value)
    private fun connection() = CatalogConnection(name = "原创书库", kind = CatalogKind.OPDS, url = server.url("/novel/").toString(), allowHttp = true)
    private fun repository() = NovelRepository(compose.activity, api = NovelApi())
    private fun waitText(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(value: String) { compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(value, substring = true)) }
    private fun screenshot(name: String) {
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { folder ->
            val bitmap = compose.runOnIdle {
                val view = if (name == "novel-intake-choice-320") requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
                    else compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            File(folder).mkdirs(); File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun browserResponse(request: RecordedRequest): MockResponse = when {
        request.path!!.startsWith("/novel/api/v1/source-browser?") -> json("""{"items":[{"id":"s","name":"原创来源","search_paginated":true,"entry_count":1}]}""")
        request.path!!.startsWith("/novel/api/v1/source-selection") -> json("""{"provider_ids":["s"]}""")
        request.path == "/novel/api/v1/library/preferences" -> json("""{"search":{},"history":[]}""")
        request.path == "/novel/api/v1/library/search-history" -> json("{}")
        request.path == "/novel/api/v1/search-jobs" -> json("""{"job_id":"search"}""")
        request.path == "/novel/api/v1/jobs/search" -> json("""{"state":"succeeded","result":{"items":[$row],"sources":[{"provider_id":"s","name":"原创来源","ok":true,"page":1,"raw_count":1,"count":1}]}}""")
        request.path == "/novel/api/v1/source-previews" -> json("""{"job_id":null,"work_id":"canonical","message":"只读预览"}""")
        else -> json("{}")
    }
    private fun workSearchAutomaticallyAssociates(serverAuto: Boolean) {
        val result = JSONObject(row)
        if (serverAuto) result.put("auto_bind", JSONObject().put("status", "bound").put("work_id", "work").put("binding_id", "b"))
        handler = { request -> when (request.path) {
            "/novel/api/v1/integration" -> json("""{"capabilities":{"source_search_modes":true,"source_intake_reuse":true,"source_search_auto_bind":$serverAuto}}""")
            "/novel/api/v1/jobs/search" -> json("""{"state":"succeeded","result":{"items":[$result]}}""")
            "/novel/api/v1/source-results/collect" -> json("""{"work_id":"work","binding_id":"b","job_id":"toc"}""")
            "/novel/api/v1/jobs/toc" -> json("""{"state":"succeeded","result":{"version_id":"v2"}}""")
            "/novel/api/v1/source-previews" -> json("""{"work_id":"work"}""")
            else -> browserResponse(request)
        } }
        val state = NovelBrowserState("山海来信")
        val target = JSONObject().put("id", "work").put("title", "山海来信").put("author", "行舟")
        var opened = ""
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { NovelSourceBrowser(repo, c, state, {}, {}, bindingWork = target,
            onBound = { fail("Automatic association must not dismiss search results") }, onRead = { opened = it.versionId }) } } }
        waitText("范围 · 1 源")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15_000) { state.autoReceipts.size == 1 }
        assertEquals(if (serverAuto) 0 else 1, requests.count { it.first.endsWith("/collect") })
        val body = JSONObject(requests.single { it.first.endsWith("/search-jobs") }.second)
        assertEquals(serverAuto, body.has("work_id"))
        if (serverAuto) assertEquals("work", body.getString("work_id"))
        compose.onNode(hasText("山海来信") and !hasSetTextAction()).performScrollTo().performClick()
        waitText("已自动关联")
        scrollTo("使用此来源继续阅读")
        compose.onNodeWithText("使用此来源继续阅读").performClick()
        compose.waitUntil(15_000) { opened == "v2" }
        assertEquals(1, requests.count { it.first.endsWith("/collect") })
        assertFalse(requests.any { it.first.contains("/progress") || it.first.contains("/chapters/") })
    }
    @Test fun existingServicesAutomaticallyAssociateExactWorkResults() = workSearchAutomaticallyAssociates(false)
    @Test fun modernServicesUseWorkScopedSearchAndPrepareDirectoryOnlyOnRead() = workSearchAutomaticallyAssociates(true)

    @Test fun compactSearchOpensPreviewAndReturningFromCanonicalWorkRetainsResultsAndPreciseScope() {
        handler = ::browserResponse
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "浅色").commit()
        val state = NovelBrowserState("山海")
        val repo = repository(); val c = connection()
        var show by mutableStateOf(true); var opened = ""
        compose.setContent { ShadowMediaTheme { Surface { if (show) NovelSourceBrowser(repo, c, state, { opened = it; show = false }, {})
            else TextButton(onClick = { show = true }) { Text("返回搜索结果") } } } }
        waitText("范围 · 1 源")
        compose.onNodeWithText("精确搜索", substring = false).performClick()
        compose.onNodeWithText("搜索", substring = false).performClick()
        waitText("山海来信")
        screenshot("novel-online-search-320")
        compose.onNodeWithText("山海来信").performClick()
        waitText("只读预览")
        scrollTo("打开已收录作品")
        compose.onNodeWithText("打开已收录作品").performClick()
        assertEquals("canonical", opened)
        compose.onNodeWithText("返回搜索结果").performClick()
        waitText("山海来信")
        assertTrue(state.precise)
        assertEquals(1, requests.count { it.first == "/novel/api/v1/search-jobs" })
        assertTrue(JSONObject(requests.single { it.first == "/novel/api/v1/search-jobs" }.second).getBoolean("precise"))
        assertFalse(requests.any { it.first.contains("collect") || it.first.contains("progress") })
    }
    @Test fun replacedSearchCancelsLateResponseWithoutMixingBooks() {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        handler = { request -> when {
            request.path == "/novel/api/v1/search-jobs" -> json("{\"job_id\":\"search${counter.incrementAndGet()}\"}")
            request.path == "/novel/api/v1/jobs/search1" -> json("{\"state\":\"succeeded\",\"result\":{\"items\":[${row.replace("山海来信", "旧结果")}],\"sources\":[]}}").setBodyDelay(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            request.path == "/novel/api/v1/jobs/search2" -> json("{\"state\":\"succeeded\",\"result\":{\"items\":[${row.replace("山海来信", "新结果")}],\"sources\":[]}}")
            else -> browserResponse(request)
        } }
        val repo = repository(); val c = connection(); val state = NovelBrowserState("旧")
        compose.setContent { ShadowMediaTheme { Surface { NovelSourceBrowser(repo, c, state, {}, {}) } } }
        waitText("范围 · 1 源")
        compose.onNodeWithText("搜索", substring = false).performClick()
        compose.waitUntil(15_000) { requests.any { it.first == "/novel/api/v1/jobs/search1" } }
        compose.runOnIdle { state.keyword = "新" }
        compose.onNodeWithText("搜索", substring = false).performClick()
        waitText("新结果")
        compose.runOnIdle { assertEquals(listOf("新结果"), state.rows.map { it.optString("name") }) }
    }
    @Test fun sourceSelectionSurvivesPagesAndMultiGroupTagsArePassedTogether() {
        handler = { request -> when {
            request.path!!.contains("providers/catalog") -> {
                val second = request.requestUrl!!.queryParameter("offset") == "40"
                json("{\"total\":41,\"groups\":[{\"id\":\"g1\",\"name\":\"组甲\"},{\"id\":\"g2\",\"name\":\"组乙\"}],\"items\":[{\"id\":\"${if (second) "b" else "a"}\",\"name\":\"${if (second) "来源乙" else "来源甲"}\",\"enabled\":true}]}")
            }
            else -> json("{}")
        } }
        var chosen: Set<String> = emptySet(); var applied: NovelSourceFilter? = null
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { NovelSourcePicker(repo, c, NovelSourceFilter(), emptySet(), {}, { f, ids -> applied = f; chosen = ids }) } } }
        waitText("来源甲")
        compose.onNodeWithText("组甲").performClick(); compose.onNodeWithText("组乙").performClick()
        compose.onNodeWithText("标签（逗号分隔，AND）").performTextInput("直连,稳定")
        scrollTo("选择本页"); compose.onNodeWithText("选择本页").performClick()
        scrollTo("下一页"); compose.onNodeWithText("下一页").performClick()
        waitText("来源乙")
        scrollTo("选择本页"); compose.onNodeWithText("选择本页").performClick()
        scrollTo("应用 · 已选 2 源"); compose.onNodeWithText("应用 · 已选 2 源").performClick()
        assertEquals(setOf("a", "b"), chosen); assertEquals(listOf("g1", "g2"), applied!!.groups); assertEquals(listOf("直连", "稳定"), applied!!.tags)
        val second = requests.last { it.first.contains("providers/catalog") }.first
        assertTrue(second.contains("group_id=g1&group_id=g2")); assertTrue(second.contains("tag="))
    }
    @Test fun previewShowsCanonicalMetadataPartialTocAndFilterWithoutCollecting() {
        handler = { request -> when (request.path) {
            "/novel/api/v1/source-previews" -> json("""{"job_id":"preview"}""")
            "/novel/api/v1/jobs/preview" -> json("""{"state":"succeeded","result":{"detail":{"name":"山海来信","word_count":"十万字","latest_chapter":"归来","source_updated_at":"今日","status":"连载"},"toc":{"items":[{"title":"初见"},{"title":"归来"}],"complete":false,"pages":3}}}""")
            else -> json("{}")
        } }
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { NovelSourcePreviewSheet(repo, c, JSONObject(row), null, {}, {}, {}, {}) } } }
        waitText("字数：十万字")
        scrollTo("部分目录")
        compose.onNodeWithText("部分目录", substring = true).assertExists()
        scrollTo("过滤预览标题")
        compose.onNodeWithText("过滤预览标题").performTextInput("归")
        scrollTo("正序"); compose.onNodeWithText("正序").performClick()
        compose.onNodeWithText("倒序").assertExists()
        assertFalse(requests.any { it.first.contains("collect") || it.first.contains("chapters") || it.first.contains("progress") })
    }
    @Test fun recipeConflictKeepsDraftAndUsesExpectedRevision() {
        handler = { request -> when (request.method) {
            "GET" -> json("""{"revision":4,"recipe":{"name":"原始规则","baseUrl":"https://example.com"}}""")
            else -> MockResponse().setResponseCode(409).setBody("""{"detail":"规则已更新"}""")
        } }
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { NovelRecipeEditor(repo, c, "s") {} } } }
        waitText("修订 4")
        val draft = """{"name":"我的修改","baseUrl":"https://example.com"}"""
        compose.onNode(hasSetTextAction()).performTextReplacement(draft)
        scrollTo("保存规则")
        compose.onNodeWithText("保存规则").performClick()
        waitText("数据已被更新")
        compose.onNode(hasSetTextAction()).assertTextContains(draft)
        val put = requests.first { it.second.contains("expected_revision") }
        assertEquals(4, JSONObject(put.second).getInt("expected_revision"))
    }
    @Test fun cancelledTaskCannotOverwriteAReplacementState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = NovelTask(scope); var result = ""
            val gate = CompletableDeferred<Unit>()
            state.run { gate.await(); result = "old" }
            state.run { result = "new" }; gate.complete(Unit)
            assertEquals("new", result); assertFalse(state.busy)
        } finally { scope.cancel() }
    }
    @Test @Config(qualifiers = "w640dp-h360dp-mdpi") fun darkLandscapeWithKeyboardSizedViewportKeepsSearchAndScopeReachable() {
        handler = ::browserResponse
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "深色").commit()
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { Box(Modifier.height(220.dp).fillMaxWidth()) { NovelSourceBrowser(repo, c, NovelBrowserState("山海"), {}, {}) } } } }
        waitText("范围 · 1 源")
        compose.onNodeWithText("范围 · 1 源").assertIsDisplayed()
        scrollTo("搜索", false)
        compose.onNodeWithText("搜索", substring = false).assertIsDisplayed()
        screenshot("novel-online-landscape-keyboard-dark")
    }
    private fun modernResponse(request: RecordedRequest): MockResponse = if (request.path == "/novel/api/v1/integration")
        json("""{"capabilities":{"source_search_modes":true,"source_chapter_open":true,"source_intake_reuse":true}}""") else browserResponse(request)

    @Test fun allModeSendsEntireSelectionAndShowsTruncation() {
        val ids = (1..25).map { "s$it" }
        handler = { request -> when {
            request.path!!.startsWith("/novel/api/v1/source-browser?") -> json(JSONObject().put("items", org.json.JSONArray(ids.map { JSONObject().put("id", it).put("name", it) })).toString())
            request.path!!.startsWith("/novel/api/v1/source-selection") -> json(JSONObject().put("provider_ids", org.json.JSONArray(ids)).toString())
            request.path == "/novel/api/v1/jobs/search" -> json(JSONObject().put("state", "succeeded").put("result", JSONObject()
                .put("mode", "all").put("items", org.json.JSONArray().put(JSONObject(row)))
                .put("sources", org.json.JSONArray(ids.map { JSONObject().put("provider_id", it).put("ok", true).put("count", 200) }))
                .put("matched_count", 5001).put("truncated", true).put("remaining", 0).put("concurrency", 8)).toString())
            else -> modernResponse(request)
        } }
        val repo = repository(); val c = connection(); val state = NovelBrowserState("山海")
        compose.setContent { ShadowMediaTheme { Surface { NovelSourceBrowser(repo, c, state, {}, {}) } } }
        waitText("范围 · 25 源"); scrollTo("全部书源"); compose.onNodeWithText("全部书源", substring = false).performClick()
        scrollTo("搜索", false); compose.onNodeWithText("搜索", substring = false).performClick()
        waitText("结果已达服务端保留上限")
        val body = JSONObject(requests.single { it.first == "/novel/api/v1/search-jobs" }.second)
        assertEquals("all", body.getString("mode")); assertEquals(25, body.getJSONArray("provider_ids").length()); assertEquals(12, body.getInt("limit"))
        compose.runOnIdle { assertTrue(state.remaining.isEmpty()); assertTrue(state.pendingIds.isEmpty()); assertEquals(5001, state.matchedCount) }
        scrollTo("结果已达服务端保留上限"); screenshot("novel-search-all-truncated-320")
    }
    @Test fun autoPauseAndCancellationKeepDifferentContinuationActionsWithoutDroppingResults() {
        val state = NovelBrowserState("山海")
        state.jobIds = listOf("s1", "s2", "s3")
        val result = JSONObject("""{"items":[$row],"sources":[{"provider_id":"s1","ok":true,"count":1}],"remaining":2}""")
        state.updateSearch(JSONObject().put("state", "cancelled").put("result", result))
        assertEquals(listOf("s2", "s3"), state.pendingIds); assertTrue(state.remaining.isEmpty()); assertEquals(1, state.rows.size)
        state.updateSearch(JSONObject().put("state", "succeeded").put("result", result))
        assertEquals(listOf("s2", "s3"), state.remaining); assertTrue(state.pendingIds.isEmpty()); assertEquals(1, state.rows.size)
    }
    @Test fun returningToResultsResumesOriginalSearchWithoutSubmittingAgain() {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        handler = { request -> if (request.path == "/novel/api/v1/jobs/search")
            json("{\"state\":\"${if (finished.get()) "succeeded" else "running"}\",\"result\":{\"items\":[$row],\"sources\":[]}}")
            else modernResponse(request)
        }
        val repo = repository(); val c = connection(); val state = NovelBrowserState("山海")
        var visible by mutableStateOf(true)
        compose.setContent { ShadowMediaTheme { Surface {
            if (visible) NovelSourceBrowser(repo, c, state, {}, {}) else TextButton(onClick = { visible = true }) { Text("返回任务") }
        } } }
        waitText("范围 · 1 源"); scrollTo("搜索", false); compose.onNodeWithText("搜索", substring = false).performClick()
        waitText("山海来信")
        compose.runOnIdle { visible = false }; waitText("返回任务"); finished.set(true)
        compose.onNodeWithText("返回任务").performClick()
        compose.waitUntil(15_000) { state.searchJobState == "succeeded" }
        assertEquals(1, requests.count { it.first == "/novel/api/v1/search-jobs" })
        assertTrue(requests.count { it.first == "/novel/api/v1/jobs/search" } >= 2)
        compose.runOnIdle { assertEquals(1, state.rows.size) }
    }
    @Test fun previewChapterClickCollectsCurrentWorkAndOpensExactVersionChapter() {
        handler = { request -> when (request.path) {
            "/novel/api/v1/source-previews" -> json("""{"job_id":null,"toc":{"items":[{"title":"归来","chapter_url":"https://example.com/chapter/2?part=1"}],"complete":true}}""")
            "/novel/api/v1/source-results/collect" -> json("""{"work_id":"existing","binding_id":"binding","job_id":"toc"}""")
            "/novel/api/v1/jobs/toc" -> json("""{"state":"succeeded","result":{"version_id":"fixed"}}""")
            "/novel/api/v1/source-results/open-chapter" -> json("""{"work_id":"existing","version_id":"fixed","chapter_id":"c2"}""")
            else -> modernResponse(request)
        } }
        val repo = repository(); val c = connection(); var opening: NovelSourceOpening? = null
        compose.setContent { ShadowMediaTheme { Surface { NovelSourcePreviewSheet(repo, c, JSONObject(row), JSONObject("""{"id":"existing","title":"已有作品"}"""), {}, {}, {}, {}, onRead = { opening = it }) } } }
        waitText("完整目录预览"); scrollTo("归来"); compose.onNodeWithText("归来", substring = false).performClick()
        compose.waitUntil(15_000) { opening != null }
        assertEquals(NovelSourceOpening("existing", "fixed", "c2"), opening)
        assertEquals("existing", JSONObject(requests.single { it.first.endsWith("/collect") }.second).getString("work_id"))
        val resolved = JSONObject(requests.single { it.first.endsWith("/open-chapter") }.second)
        assertEquals("https://example.com/chapter/2?part=1", resolved.getString("chapter_url")); assertEquals("fixed", resolved.getString("version_id"))
        assertFalse(requests.any { it.first.contains("/chapters/") || it.first.contains("/progress/") })
    }
    @Test fun ambiguousReadWaitsForInlineWorkChoiceThenResumesTheSameAction() {
        handler = { request -> when (request.path) {
            "/novel/api/v1/source-previews" -> json("""{"job_id":null,"message":"只读预览"}""")
            "/novel/api/v1/source-results/collect" -> if (!JSONObject(request.body.clone().readUtf8()).has("work_id"))
                MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"choose_existing_work","candidates":[{"id":"chosen","title":"山海来信","author":"行舟","description":"库内已有简介"}]}}""")
                else json("""{"work_id":"chosen","binding_id":"binding","job_id":"toc"}""")
            "/novel/api/v1/jobs/toc" -> json("""{"state":"succeeded","result":{"version_id":"fixed"}}""")
            else -> modernResponse(request)
        } }
        val repo = repository(); val c = connection(); var opening: NovelSourceOpening? = null
        compose.setContent { ShadowMediaTheme { Surface { NovelSourcePreviewSheet(repo, c, JSONObject(row), null, {}, {}, {}, {}, onRead = { opening = it }) } } }
        waitText("只读预览"); scrollTo("阅读此来源"); compose.onNodeWithText("阅读此来源").performClick()
        waitText("选择已有作品"); assertNull(opening)
        assertEquals(1, requests.count { it.first.endsWith("/collect") })
        scrollTo("关联《山海来信》"); screenshot("novel-intake-choice-320")
        compose.onNodeWithText("关联《山海来信》", substring = true).performClick()
        compose.waitUntil(15_000) { opening != null }
        assertEquals(NovelSourceOpening("chosen", "fixed"), opening)
        val bodies = requests.filter { it.first.endsWith("/collect") }.map { JSONObject(it.second) }
        assertEquals(2, bodies.size); assertEquals("chosen", bodies.last().getString("work_id")); assertTrue(bodies.none { it.has("create_new") })
    }
    @Test fun independentCollectionIsOnlySentAfterExplicitChoice() {
        handler = { request -> when (request.path) {
            "/novel/api/v1/source-previews" -> json("""{"job_id":null,"message":"只读预览"}""")
            "/novel/api/v1/source-results/collect" -> if (!JSONObject(request.body.clone().readUtf8()).optBoolean("create_new"))
                MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"choose_existing_work","candidates":[{"id":"existing","title":"山海来信","author":""}]}}""")
                else json("""{"work_id":"separate"}""")
            else -> modernResponse(request)
        } }
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { NovelSourcePreviewSheet(repo, c, JSONObject(row), null, {}, {}, {}, {}) } } }
        waitText("只读预览"); scrollTo("收录作品"); compose.onNodeWithText("收录作品", substring = false).performClick()
        waitText("选择已有作品"); scrollTo("作为独立作品收录"); compose.onNodeWithText("作为独立作品收录").performClick()
        compose.waitUntil(15_000) { requests.count { it.first.endsWith("/collect") } == 2 }
        val bodies = requests.filter { it.first.endsWith("/collect") }.map { JSONObject(it.second) }
        assertFalse(bodies.first().has("create_new")); assertTrue(bodies.last().getBoolean("create_new")); assertFalse(bodies.last().has("work_id"))
    }
    @Test fun metadataSourceWithTocNeverOffersChapterReading() {
        handler = { request -> if (request.path == "/novel/api/v1/source-previews")
            json("""{"job_id":null,"toc":{"items":[{"title":"仅供浏览章节","chapter_url":"https://example.com/c"}],"complete":true}}""") else modernResponse(request)
        }
        val metadata = JSONObject(row).put("capabilities", org.json.JSONArray(listOf("search", "detail", "toc")))
        val repo = repository(); val c = connection()
        compose.setContent { ShadowMediaTheme { Surface { NovelSourcePreviewSheet(repo, c, metadata, null, {}, {}, {}, {}) } } }
        waitText("完整目录预览"); scrollTo("仅供浏览章节")
        compose.onNodeWithText("仅供浏览章节").assertHasNoClickAction()
        compose.onNodeWithText("阅读此来源").assertDoesNotExist()
        assertFalse(requests.any { it.first.endsWith("/collect") || it.first.endsWith("/open-chapter") })
    }
    private fun scrollTo(value: String, substring: Boolean) { compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(value, substring = substring)) }
}
