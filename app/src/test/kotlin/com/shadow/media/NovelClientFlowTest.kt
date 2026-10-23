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
            val bitmap = compose.runOnIdle { val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
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
        compose.onNodeWithText("正序").performClick()
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
    private fun scrollTo(value: String, substring: Boolean) { compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(value, substring = substring)) }
}
