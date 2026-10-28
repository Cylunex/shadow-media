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
import com.shadow.media.library.*
import com.shadow.media.ui.theme.ShadowMediaTheme
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelModuleNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val books = """[{"id":"1","title":"山海来信","author":"行舟"},{"id":"2","title":"长夜与星河","author":"林间"},{"id":"3","title":"远山灯火","author":"西川"},{"id":"4","title":"群山之间","author":"行舟"},{"id":"5","title":"听风经过","author":"青禾"},{"id":"6","title":"春日手记","author":"北岛"}]"""
    @Before fun start() {
        try { androidx.work.WorkManager.getInstance(compose.activity.applicationContext) }
        catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(compose.activity.applicationContext, androidx.work.Configuration.Builder().build())
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path.orEmpty()
                val body = when {
                    request.requestUrl!!.encodedPath.endsWith("/city/home") -> """{"total":6,"sections":[]}"""
                    request.requestUrl!!.encodedPath.endsWith("/works/1") -> """{"id":"1","title":"山海来信","author":"行舟","description":"群山深处的一封来信。"}"""
                    request.requestUrl!!.encodedPath.endsWith("/works") -> """{"total":6,"items":$books}"""
                    else -> "{}"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }
    @After fun stop() { server.shutdown() }
    private fun waitText(text: String) {
        try { compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { throw AssertionError("等待 $text；请求：$requests\n${compose.onRoot().printToString()}", failure) }
    }
    private fun screenshot(name: String) {
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { directory ->
            val bitmap = compose.runOnIdle { val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun launch(theme: String) {
        val connection = CatalogConnection(name = "测试书库", kind = CatalogKind.OPDS, url = server.url("/novel/").toString(), allowHttp = true)
        val repository = NovelRepository(compose.activity, api = NovelApi(), connectionLoader = { listOf(connection) })
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", theme).commit()
        var destination by mutableStateOf(MediaDestination.SHELF)
        compose.setContent { ShadowMediaTheme {
            Scaffold(topBar = { MediaModuleHeader(MediaModule.NOVEL, {}, {}, {}) },
                bottomBar = { MediaModuleNavigation(MediaModule.NOVEL, destination, { destination = it }) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    NovelScreen(repository, {}, rootSection = destination, onLocalShelf = {})
                }
            }
        } }
        waitText("山海来信")
        compose.waitUntil(15_000) { requests.any { it.contains("/navigation?") } }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription("刷新") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    @Test fun bottomPagesKeepTheirOwnHistoryWhenReturningFromDiscovery() {
        launch("深色")
        screenshot("media-novel-screen-dark-393")
        compose.onNodeWithText("山海来信").performClick()
        waitText("群山深处的一封来信。")
        compose.onNodeWithTag("destination-DISCOVERY").performClick()
        waitText("主题书单")
        compose.onNodeWithTag("destination-SHELF").performClick()
        waitText("群山深处的一封来信。")
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("长夜与星河")
        compose.onNodeWithTag("destination-CITY").performClick()
        waitText("今天，读点什么")
        compose.onNodeWithTag("destination-SHELF").performClick()
        waitText("长夜与星河")
        compose.waitUntil(15_000) { requests.count { it.contains("/works?") && it.contains("view=shelf") } >= 2 }
        assertTrue(requests.any { it.contains("/city/home") })
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun compactScreenKeepsShelfAndToolsReachable() {
        launch("浅色")
        compose.onNodeWithContentDescription("全部筛选").assertIsDisplayed()
        compose.onNodeWithTag("destination-BOOK_SOURCES").assertIsDisplayed()
        screenshot("media-novel-screen-light-320")
        compose.onNodeWithContentDescription("书库选项").performClick()
        compose.onNodeWithText("本机书架与导入").assertIsDisplayed()
        compose.onNodeWithText("阅读历史").assertIsDisplayed()
    }
}
