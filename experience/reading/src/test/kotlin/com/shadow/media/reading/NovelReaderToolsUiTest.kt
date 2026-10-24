package com.shadow.media.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
    private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request.path.orEmpty() to request.body.clone().readUtf8()
            return handler(request)
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

    @Test fun sourceSelectionShowsTargetContentsAndOnlyExplicitChapterMovesReading() {
        handler = { request -> if (request.path == "/api/v1/works/work") json("""{"id":"work","versions":[{"id":"v1","state":"ready","label":"主书源"},{"id":"v2","state":"ready","label":"备用书源"}]}""")
            else json("""{"version_id":"v2","total":1,"offset":0,"limit":100,"next_offset":null,"items":[{"id":"next","title":"山海来信","ordinal":8,"readable":true}]}""") }
        val repo = repository(); val state = NovelReaderSourceState(); var opened = ""
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
            NovelReaderSourcesPanel(repo, publication(), "示例小说", "山海来信", true, state) { version, id -> opened = "$version/$id" }
        } } } }
        waitText("备用书源")
        screenshot("novel-reader-sources-320")
        compose.onNodeWithText("备用书源").performClick()
        waitText("接着读当前章")
        assertEquals("", opened)
        compose.onNodeWithText("接着读当前章").performClick()
        assertEquals("v2/next", opened)
        assertFalse(requests.any { it.first.contains("progress") || it.first.endsWith("/open") })
    }

    @Test fun searchingAndAssociatingFromReaderRetainsTheExistingWork() {
        val source = """{"provider_id":"s","provider_name":"备用站点","bookUrl":"https://example.com/book","name":"山海来信","author":"行舟","capabilities":["detail","toc","chapter"]}"""
        handler = { request -> when (request.path) {
            "/api/v1/works/work" -> json("""{"id":"work","versions":[{"id":"v1","label":"主书源","state":"ready"},{"id":"v2","label":"备用书源","state":"ready"}]}""")
            "/api/v1/integration" -> json("""{"capabilities":{"source_search_modes":true,"source_intake_reuse":true}}""")
            "/api/v1/search-jobs" -> json("""{"job_id":"search"}""")
            "/api/v1/jobs/search" -> json("""{"state":"succeeded","result":{"items":[$source]}}""")
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
        waitText("关联并选择章节")
        compose.onNodeWithText("关联并选择章节").performScrollTo().performClick()
        waitText("续读此来源")
        val body = JSONObject(requests.single { it.first == "/api/v1/source-results/collect" }.second)
        assertEquals("work", body.getString("work_id")); assertFalse(body.has("create_new"))
        assertEquals("v2", state.selectedVersion)
        assertFalse(requests.any { it.first.contains("progress") || it.first == "/api/v1/works" })
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
