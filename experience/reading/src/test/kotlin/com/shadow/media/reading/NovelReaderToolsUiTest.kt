package com.shadow.media.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelReaderToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var c: CatalogConnection
    private var browserData = """{"items":[{"id":"s"}],"groups":[],"tags":[]}"""
    private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request.path.orEmpty() to request.body.clone().readUtf8()
            return if (request.path.orEmpty().startsWith("/api/v1/source-browser")) json(browserData) else handler(request)
        } }; server.start()
        c = CatalogConnection(name = "测试书库", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true)
    }
    @After fun teardown() { server.shutdown() }
    private fun json(value: String) = MockResponse().setBody(value)
    private fun repository() = NovelRepository(compose.activity, api = NovelApi(), connectionLoader = { listOf(c) })
    private fun publication() = NovelPublicationEntity("current", novelScope(c), "work", "v1", "{}", "r")
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name: String) {
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { path ->
            val bitmap = compose.runOnIdle { val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
            File(path).mkdirs(); File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun reopeningSourcesKeepsRemoteRowsAndStableScrollWhileRefreshFails() {
        var shown by mutableStateOf(true)
        var failRefresh = false
        val versions = (1..40).joinToString(",") { """{"id":"v$it","state":"ready","label":"来源 $it"}""" }
        handler = { request -> if (request.path == "/api/v1/works/work") {
            if (failRefresh) MockResponse().setResponseCode(503).setBody("{}").setBodyDelay(1, TimeUnit.SECONDS)
            else json("""{"id":"work","versions":[$versions]}""")
        } else json("{}") }
        val repo = repository(); val state = NovelReaderSourceState(); val pub = publication()
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize()) {
            if (shown) NovelReaderSourcesPanel(repo, pub, "示例小说", "当前章", true, state) { _, _ -> }
        } } } }
        compose.waitUntil(10_000) { state.catalogs[pub.scope to pub.workId]?.versions?.size == 40 }
        val catalog = state.catalogs.getValue(pub.scope to pub.workId)
        compose.runOnIdle { runBlocking { catalog.list.scrollToItem(20, 7) } }
        val initialKey = compose.runOnIdle { catalog.list.layoutInfo.visibleItemsInfo.first().key }
        compose.onNodeWithText("来源 20").performClick()
        waitText("返回书源列表")
        compose.onNodeWithText("返回书源列表").performClick()
        compose.waitForIdle()
        assertEquals(initialKey, compose.runOnIdle { catalog.list.layoutInfo.visibleItemsInfo.first().key })
        compose.runOnIdle { shown = false; failRefresh = true }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertEquals(40, compose.runOnIdle { catalog.versions.size })
        assertEquals(initialKey, compose.runOnIdle { catalog.list.layoutInfo.visibleItemsInfo.first().key })
        waitText("HTTP 503")
        assertEquals(40, compose.runOnIdle { catalog.versions.size })
        assertEquals(initialKey, compose.runOnIdle { catalog.list.layoutInfo.visibleItemsInfo.first().key })
        assertFalse(requests.any { it.first.contains("progress") && it.second.isNotBlank() })
    }

    @Test fun tagsAndGroupsPersistWhileRetryAndContinuationKeepSubmittedScope() {
        browserData = """{"items":[{"id":"s"},{"id":"s2"}],"groups":[{"id":"a","name":"常用"},{"id":"b","name":"备用"}],"tags":["稳定","快速"]}"""
        var jobs = 0
        handler = { request -> when {
            request.path == "/api/v1/works/work" -> json("""{"id":"work","title":"山海来信","author":"行舟","versions":[]}""")
            request.path == "/api/v1/integration" -> json("""{"capabilities":{"source_search_modes":true}}""")
            request.path.orEmpty().startsWith("/api/v1/source-selection") -> json("""{"provider_ids":["s","s2"]}""")
            request.path == "/api/v1/search-jobs" -> json("""{"job_id":"job${++jobs}"}""")
            request.path == "/api/v1/jobs/job1" -> json("""{"state":"succeeded","result":{"remaining":1,"items":[],"sources":[{"provider_id":"s","ok":false}]}}""")
            request.path == "/api/v1/jobs/job2" -> json("""{"state":"succeeded","result":{"remaining":0,"items":[],"sources":[{"provider_id":"s","ok":true}]}}""")
            else -> json("""{"state":"succeeded","result":{"remaining":0,"items":[],"sources":[{"provider_id":"s2","ok":true}]}}""")
        } }
        var shown by mutableStateOf(true)
        val state = NovelReaderSourceState(); val repo = repository()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            if (shown) NovelReaderSourcesPanel(repo, publication(), "山海来信", "当前章", true, state) { _, _ -> fail("Scope must not open reading") }
        } } }
        waitText("搜索范围 ·")
        compose.onNodeWithText("搜索范围 ·", substring = true).performClick()
        waitText("稳定")
        compose.onNodeWithText("常用", substring = false).performClick()
        compose.onNodeWithText("稳定", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("应用范围").performClick()
        compose.onNodeWithText("搜索可用书源").performScrollTo().performClick()
        compose.waitUntil(10_000) { state.jobState == "succeeded" }
        assertEquals(1, state.result!!.getInt("remaining"))
        val selection = requests.single { it.first.startsWith("/api/v1/source-selection") }.first
        assertTrue(selection.contains("group_id=a")); assertTrue(selection.contains("tag="))
        compose.onNodeWithText("搜索范围 ·", substring = true).performClick()
        compose.onNodeWithText("常用", substring = false).performClick()
        compose.onNodeWithText("备用", substring = false).performClick()
        compose.onNodeWithText("应用范围").performClick()
        compose.onNodeWithText("书名或作者").performScrollTo().performTextReplacement("新的关键词")
        compose.onNodeWithText("重试未完成的 1 个来源").performScrollTo().performClick()
        compose.waitUntil(10_000) { state.jobId == "job2" && state.jobState == "succeeded" }
        assertEquals(1, state.result!!.getInt("remaining"))
        compose.onNodeWithText("继续搜索 1 源").performClick()
        compose.waitUntil(10_000) { state.jobId == "job3" && state.jobState == "succeeded" }
        val bodies = requests.filter { it.first == "/api/v1/search-jobs" }.map { JSONObject(it.second) }
        bodies.forEach { assertEquals("山海来信", it.getString("q")); assertEquals(listOf("a"), it.novelStrings("group_ids")) }
        assertEquals(listOf("s"), bodies[1].novelStrings("provider_ids"))
        assertEquals(listOf("s2"), bodies[2].novelStrings("provider_ids"))
        assertEquals(setOf("s", "s2"), state.result!!.novelObjects("sources").map { it.getString("provider_id") }.toSet())
        compose.runOnIdle { shown = false }; compose.waitForIdle(); compose.runOnIdle { shown = true }
        waitText("搜索范围 ·")
        assertEquals(listOf("b"), state.filter.groups); assertEquals(listOf("稳定"), state.filter.tags)
        assertEquals(listOf("b"), NovelSourceScopeStore(compose.activity).load("${c.id}:reader")!!.filter.groups)
        assertEquals(3, requests.count { it.first == "/api/v1/search-jobs" })
        assertFalse(requests.any { it.first.contains("progress") || it.first.endsWith("/collect") })
    }

    @Test fun sourceDirectoryKeepsPageAndScrollAcrossReturnAndSheetClose() {
        handler = { request -> if (request.path == "/api/v1/works/work") json("""{"id":"work","versions":[{"id":"v1","state":"ready","label":"主书源"}]}""")
            else {
                val offset = request.requestUrl?.queryParameter("offset")?.toIntOrNull() ?: 0
                val rows = (offset until offset + 100).joinToString(",") { """{"id":"c$it","title":"章节 $it","ordinal":$it,"readable":true}""" }
                json("""{"version_id":"v1","total":200,"offset":$offset,"limit":100,"next_offset":${if (offset == 0) "100" else "null"},"items":[$rows]}""")
            }
        }
        var shown by mutableStateOf(true)
        val state = NovelReaderSourceState(); val repo = repository()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            if (shown) NovelReaderSourcesPanel(repo, publication(), "示例小说", "章节 0", true, state, "c0", 0) { _, _ -> fail("Preview must not open reading") }
        } } }
        waitText("主书源")
        compose.onNodeWithText("主书源 · 当前").performClick()
        waitText("章节 0")
        compose.onNodeWithContentDescription("下一页目录").performClick()
        waitText("章节 100")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("章节 120"))
        compose.onNodeWithText("返回书源列表").performClick()
        compose.onNodeWithText("主书源 · 当前").performClick()
        waitText("章节 120")
        compose.onNodeWithText("章节 120").assertIsDisplayed()
        compose.runOnIdle { shown = false }; compose.waitForIdle(); compose.runOnIdle { shown = true }
        waitText("章节 120")
        compose.onNodeWithText("章节 120").assertIsDisplayed()
        val contents = requests.filter { it.first.contains("/contents?") }
        assertTrue(contents.last().first.contains("offset=100"))
        assertFalse(contents.last().first.contains("anchor_id="))
        assertFalse(requests.any { it.first.contains("progress") })
    }

    @Test fun failedRefreshKeepsOldRowsAndEmptyTagScopeNeverSearchesAllSources() {
        handler = { request -> when {
            request.path == "/api/v1/works/work" -> json("""{"id":"work","title":"山海来信","author":"行舟","versions":[]}""")
            request.path == "/api/v1/integration" -> json("""{"capabilities":{"source_search_modes":true}}""")
            request.path.orEmpty().startsWith("/api/v1/source-selection") -> json("""{"provider_ids":[]}""")
            request.path == "/api/v1/search-jobs" -> json("""{"job_id":"failed"}""")
            else -> json("""{"state":"failed","error":"查询中断","result":{"items":[],"sources":[{"provider_id":"s","ok":false}]}}""")
        } }
        val state = NovelReaderSourceState().apply {
            query = "新查询"; submitted = "旧查询"
            result = JSONObject("""{"items":[{"provider_id":"old","bookUrl":"https://example.com/old","name":"旧结果","author":"另一作者","capabilities":["detail","toc","chapter"]}],"sources":[{"provider_id":"old","ok":true}]}""")
        }
        val repo = repository()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            NovelReaderSourcesPanel(repo, publication(), "山海来信", "当前章", true, state) { _, _ -> fail("Search must not move reading") }
        } } }
        waitText("搜索可用书源")
        compose.onNodeWithText("搜索可用书源").performScrollTo().performClick()
        waitText("重试原搜索")
        assertNotNull(state.previousResult)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("旧结果"))
        compose.onNodeWithText("旧结果").assertIsDisplayed()
        compose.runOnIdle { state.filter = state.filter.copy(tags = listOf("无匹配标签")) }
        compose.onNodeWithText("搜索可用书源").performScrollTo().performClick()
        waitText("当前范围没有可搜索书源")
        assertEquals(1, requests.count { it.first == "/api/v1/search-jobs" })
        assertNotNull(state.previousResult)
        assertFalse(requests.any { it.first.contains("progress") || it.first.endsWith("/collect") })
    }

    @Test fun readingShortcutsAreAvailableDirectlyInTheHeader() {
        var selected = ""
        compose.setContent { MaterialTheme { NativeReaderHeader("示例小说", "第一章", "已同步", true,
            onBack = {}, onClose = {}, onBookmark = {}, sources = true, onPanel = { selected = it }) } }
        compose.onNodeWithText("换源").assertIsDisplayed().performClick()
        assertEquals("书源", selected)
        compose.onNodeWithText("替换").assertIsDisplayed().performClick()
        assertEquals("替换", selected)
        compose.onNodeWithText("搜正文").assertIsDisplayed()
        compose.onNodeWithText("书签").assertIsDisplayed()
        screenshot("novel-reader-header-shortcuts-320")
    }

    @Test fun continuationIsVisibleAboveLongSourceListsAndRetainsEarlierSources() {
        handler = { request -> when (request.path) {
            "/api/v1/works/work" -> json("""{"id":"work","versions":[],"title":"山海来信","author":"行舟"}""")
            "/api/v1/integration" -> json("""{"capabilities":{"source_search_modes":true}}""")
            "/api/v1/search-jobs" -> json("""{"job_id":"next"}""")
            "/api/v1/jobs/next" -> json("""{"state":"succeeded","result":{"remaining":0,"items":[],"sources":[{"provider_id":"new","ok":true}]}}""")
            else -> json("{}")
        } }
        val repo = repository()
        val state = NovelReaderSourceState().apply {
            submitted = "山海来信"
            result = JSONObject("""{"remaining":30,"items":[],"sources":[{"provider_id":"old","ok":true}]}""")
        }
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
            NovelReaderSourcesPanel(repo, publication(), "山海来信", "当前章", true, state) { _, _ -> }
        } } } }
        waitText("继续搜索 30 源")
        compose.onNodeWithText("继续搜索 30 源").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { state.jobState == "succeeded" }
        val body = JSONObject(requests.single { it.first == "/api/v1/search-jobs" }.second)
        assertEquals(listOf("old"), body.novelStrings("exclude_ids"))
        assertEquals(setOf("old", "new"), state.result!!.novelObjects("sources").map { it.getString("provider_id") }.toSet())
    }

    @Test fun sourceSelectionShowsTargetContentsAndOnlyExplicitChapterMovesReading() {
        handler = { request -> if (request.path == "/api/v1/works/work") json("""{"id":"work","versions":[{"id":"v1","state":"ready","label":"主书源"},{"id":"v2","state":"ready","label":"备用书源"}]}""")
            else if (request.path!!.contains("/open")) json("""{"version_id":"v2","quality":"approximate","mapping_basis":"title","position":{"chapter_id":"next"}}""")
            else json("""{"version_id":"v2","total":1,"offset":0,"limit":100,"next_offset":null,"items":[{"id":"next","title":"山海来信","ordinal":8,"readable":true}]}""") }
        val repo = repository(); val state = NovelReaderSourceState(); var opened = ""
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
            NovelReaderSourcesPanel(repo, publication(), "示例小说", "山海来信", true, state) { version, id -> opened = "$version/$id" }
        } } } }
        waitText("备用书源")
        screenshot("novel-reader-sources-320")
        compose.onNodeWithText("备用书源").performClick()
        waitText("从同名章开头读")
        assertEquals("", opened)
        compose.onNodeWithText("从同名章开头读").performClick()
        assertEquals("v2/next", opened)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("搜索其他书源")
        assertNull(state.selectedVersion)
        assertFalse(requests.any { it.first.contains("progress") })
    }

    @Test fun partialSearchResultsCanOpenTheirDirectoryWhileOtherSourcesAreStillSearching() {
        val source = """{"provider_id":"s","provider_name":"备用站点","bookUrl":"https://example.com/book","name":"山海来信","author":"行舟","capabilities":["detail","toc","chapter"],"auto_bind":{"status":"bound","work_id":"work","binding_id":"b"}}"""
        handler = { request -> when (request.path) {
            "/api/v1/works/work" -> json("""{"id":"work","title":"山海来信","author":"行舟","versions":[{"id":"v1","label":"主书源","state":"ready"},{"id":"v2","label":"备用书源","state":"ready"}]}""")
            "/api/v1/integration" -> json("""{"capabilities":{"source_search_modes":true,"source_intake_reuse":true,"source_search_auto_bind":true}}""")
            "/api/v1/search-jobs" -> json("""{"job_id":"search"}""")
            "/api/v1/jobs/search" -> json("""{"state":"running","result":{"items":[$source]}}""")
            "/api/v1/source-results/collect" -> json("""{"work_id":"work","binding_id":"b","job_id":"toc"}""")
            "/api/v1/jobs/toc" -> json("""{"state":"succeeded","result":{"version_id":"v2"}}""")
            else -> json("""{"version_id":"v2","total":0,"offset":0,"limit":100,"next_offset":null,"items":[]}""")
        } }
        val repo = repository(); val state = NovelReaderSourceState()
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
            NovelReaderSourcesPanel(repo, publication(), "山海来信", "当前章", true, state) { _, _ -> fail("Search must not start reading") }
        } } } }
        waitText("主书源")
        compose.onNodeWithText("搜索可用书源").performScrollTo().performClick()
        compose.waitUntil(10_000) { state.autoReceipts.size == 1 }
        assertFalse(requests.any { it.first.endsWith("/collect") || it.first == "/api/v1/jobs/toc" })
        assertEquals("work", JSONObject(requests.single { it.first == "/api/v1/search-jobs" }.second).getString("work_id"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("已自动关联 · 选择章节"))
        compose.onNodeWithText("已自动关联 · 选择章节").performClick()
        waitText("续读此来源")
        val body = JSONObject(requests.single { it.first == "/api/v1/source-results/collect" }.second)
        assertEquals("work", body.getString("work_id")); assertFalse(body.has("create_new"))
        assertEquals("v2", state.selectedVersion)
        assertEquals("running", state.jobState)
        assertFalse(requests.any { it.first.contains("progress") || it.first == "/api/v1/works" })
    }

    @Test fun stoppingSearchRetainsResultsAndFilteringDoesNotLaunchAnotherSearch() {
        val source = """{"provider_id":"s","provider_name":"备用站点","bookUrl":"https://example.com/book","name":"山海来信","author":"另一作者","capabilities":["detail","toc","chapter"]}"""
        val result = """"result":{"items":[$source],"sources":[{"provider_id":"s","ok":true,"auto_bind_complete":false}]}"""
        handler = { request -> when (request.path) {
            "/api/v1/works/work" -> json("""{"id":"work","title":"山海来信","author":"行舟","versions":[]}""")
            "/api/v1/integration" -> json("""{"capabilities":{"source_search_auto_bind":true}}""")
            "/api/v1/search-jobs" -> json("""{"job_id":"search"}""")
            "/api/v1/jobs/search" -> json("""{"state":"running",$result}""")
            "/api/v1/jobs/search/cancel" -> json("""{"state":"cancelled",$result}""")
            else -> json("{}")
        } }
        val repo = repository(); val state = NovelReaderSourceState()
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
            NovelReaderSourcesPanel(repo, publication(), "山海来信", "当前章", true, state) { _, _ -> fail("Filtering must not open a book") }
        } } } }
        waitText("搜索可用书源")
        compose.onNodeWithText("搜索可用书源").performScrollTo().performClick()
        waitText("关联并选择章节")
        compose.onNodeWithText("停止搜索").assertIsDisplayed().performClick()
        waitText("搜索已停止，已返回结果保留")
        compose.onNodeWithText("只看同名同作者").performScrollTo().performClick()
        waitText("暂无同名同作者结果")
        compose.onNodeWithText("关联并选择章节").assertDoesNotExist()
        compose.onNodeWithText("只看同名同作者").performScrollTo().performClick()
        waitText("关联并选择章节")
        assertEquals(1, requests.count { it.first == "/api/v1/search-jobs" })
        assertEquals(1, requests.count { it.first.endsWith("/cancel") })
        assertFalse(requests.any { it.first.endsWith("/collect") })
        assertEquals("cancelled", state.jobState)
        compose.onNodeWithText("书名或作者").performScrollTo().performTextReplacement("不同关键词")
        compose.onNodeWithText("重试未完成的 1 个来源").performScrollTo().performClick()
        compose.waitUntil(10_000) { requests.count { it.first == "/api/v1/search-jobs" } == 2 }
        val retry = JSONObject(requests.last { it.first == "/api/v1/search-jobs" }.second)
        assertEquals("山海来信", retry.getString("q"))
        assertEquals(listOf("s"), retry.novelStrings("provider_ids"))
        assertEquals(1, state.result!!.novelObjects("items").size)
    }

    @Test fun interruptedSearchResumesTheSameJobAndRetainsPartialResults() {
        var polls = 0
        handler = { request -> when (request.path) {
            "/api/v1/works/work" -> json("""{"id":"work","title":"山海来信","author":"行舟","versions":[]}""")
            "/api/v1/search-jobs" -> json("""{"job_id":"search"}""")
            "/api/v1/jobs/search" -> when (++polls) {
                1 -> json("""{"state":"running","result":{"items":[{"name":"已返回作品"}]}}""")
                2 -> json("""{"detail":{"message":"暂时断网"}}""").setResponseCode(503)
                else -> json("""{"state":"succeeded","result":{"items":[{"name":"已返回作品"},{"name":"后续作品"}]}}""")
            }
            else -> json("{}")
        } }
        val repo = repository(); val state = NovelReaderSourceState()
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
            NovelReaderSourcesPanel(repo, publication(), "山海来信", "当前章", true, state) { _, _ -> }
        } } } }
        waitText("搜索可用书源")
        compose.onNodeWithText("搜索可用书源").performScrollTo().performClick()
        waitText("继续获取结果")
        assertEquals(1, state.result!!.novelObjects("items").size)
        compose.onNodeWithText("继续获取结果").performScrollTo().performClick()
        compose.waitUntil(10_000) { state.jobState == "succeeded" }
        assertEquals(2, state.result!!.novelObjects("items").size)
        assertEquals(1, requests.count { it.first == "/api/v1/search-jobs" })
    }

    @Test fun aPartialDownloadHasRetryAndNeverClaimsOfflineFullBook() {
        var retries = 0
        val task = NovelDownloadTask(novelScope(c), "work", "v1", "示例", state = "partial", completed = 8, total = 10, failed = 2)
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            NovelDownloadProgress(task, true, true, {}, { retries++ })
        } } } }
        compose.onNodeWithText("部分章节失败 · 8 / 10 章 · 2 章失败").assertIsDisplayed()
        compose.onNodeWithText("全本已下载").assertDoesNotExist()
        compose.onNodeWithText("重试未完成章节").performClick()
        assertEquals(1, retries)
        screenshot("novel-download-partial-320")
    }

    @Test fun anIncompleteSourceAndRangeRemainDifferentFromAFullBook() {
        val task = NovelDownloadTask(novelScope(c), "work", "v1", "示例", state = "completed", completed = 100, total = 100, versionComplete = false)
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            NovelDownloadProgress(task, true, true, {}, {})
        } } } }
        compose.onNodeWithText("所选目录已下载 · 100 / 100 章").assertIsDisplayed()
        compose.onNodeWithText("书源目录尚不完整，已下载当前版本全部可读章节。").assertIsDisplayed()
        compose.onNodeWithText("全本已下载").assertDoesNotExist()
    }

    @Test fun downloadOptionsValidateRangeAndKeepWifiDefaultAtCompactWidth() {
        val repo = repository()
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(8.dp)) {
            NovelDownloadPanel(repo, c, novelScope(c), "work", "v1", 29)
        } } } }
        compose.onNodeWithText("下载全本").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("选择范围").performClick()
        compose.onNodeWithText("缓存", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("结束序号").performTextInput("20")
        compose.onNodeWithText("缓存", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("结束序号").performTextClearance()
        compose.onNodeWithText("结束序号").performTextInput("80")
        compose.onNodeWithText("缓存", substring = false).assertIsEnabled()
        screenshot("novel-download-options-320")
        assertTrue(requests.isEmpty())
    }

    @Test @Config(qualifiers = "w640dp-h360dp-mdpi") fun downloadDialogScrollsAtLandscapeHeightAndClosingRestoresContents() {
        handler = { json("""{"version_id":"v1","total":1,"offset":0,"limit":100,"next_offset":null,"items":[{"id":"chapter","title":"山海来信","ordinal":0,"readable":true}]}""") }
        val repo = repository()
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(8.dp)) {
            NovelContentsPanel(repo, c, "work", "v1", true, {}, Modifier.weight(1f))
        } } } }
        waitText("山海来信")
        compose.onNodeWithText("缓存与下载", substring = false).performClick()
        compose.onNodeWithText("下载全本").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择范围").performScrollTo().performClick()
        compose.onNodeWithText("结束序号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭缓存与下载").performScrollTo().performClick()
        compose.onNodeWithText("山海来信").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页目录").assertIsDisplayed()
    }
}
