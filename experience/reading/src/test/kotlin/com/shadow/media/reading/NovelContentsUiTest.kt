package com.shadow.media.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.shadow.media.library.NovelContentsQuery
import com.shadow.media.library.NovelRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelContentsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun chapter(id: Int, readable: Boolean = true) = JSONObject().put("id", "c$id").put("title", "第$id 节 山海来信").put("ordinal", id).put("readable", readable)
    private fun page() = JSONObject().put("version_id", "v").put("offset", 100).put("limit", 100).put("total", 2400)
        .put("next_offset", 200).put("latest", chapter(2399)).put("items", JSONArray((100..199).map { chapter(it, it != 101) }))
    @Test fun searchResetsPagingReverseKeepsSearchAndCurrentChapterClearsBoth() {
        var query by mutableStateOf(NovelContentsQuery("v", offset = 100))
        var opened: String? = null
        val data = page()
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("章节目录", style = MaterialTheme.typography.titleLarge)
            NovelContentsBody(query, data, false, null, setOf("c145"), "c145", 145, true,
                onQuery = { next, _ -> query = next }, onRetry = {}, onOpen = { opened = it })
        } } } }
        compose.onNodeWithText("正在阅读").assertIsDisplayed()
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { folder ->
            val bitmap = compose.runOnIdle { val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
            File(folder).mkdirs()
            File(folder, "novel-contents-search.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("搜索全书章节标题").performTextInput("远山")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals("远山", query.q); assertEquals(0, query.offset) }
        compose.onNodeWithText("正序").performClick()
        compose.runOnIdle { assertTrue(query.descending); assertEquals("远山", query.q) }
        compose.onNodeWithText("当前章").performClick()
        compose.runOnIdle { assertEquals(NovelContentsQuery("v", offset = 100), query) }
        compose.onNodeWithText("读末章").performClick()
        assertEquals("c2399", opened)
        compose.onNodeWithContentDescription("下一页目录").performClick()
        compose.runOnIdle { assertEquals(200, query.offset) }
    }

    @Test fun backgroundCacheUpdatesKeepTheScrolledDirectoryPosition() {
        var data by mutableStateOf(page())
        var downloaded by mutableStateOf(emptySet<String>())
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            NovelContentsBody(NovelContentsQuery("v", offset = 100), data, false, null, downloaded,
                "c145", 145, true, { _, _ -> }, {}, {})
        } } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        compose.onNodeWithText("第199 节 山海来信").assertIsDisplayed()
        compose.runOnIdle {
            downloaded = setOf("c198", "c199")
            data = JSONObject(data.toString()).put("_cached_at", 1L)
        }
        compose.waitForIdle()
        compose.onNodeWithText("第199 节 山海来信").assertIsDisplayed()
        compose.onNodeWithText("当前章").performClick()
        compose.onNodeWithText("第145 节 山海来信").assertIsDisplayed()
    }

    @Test fun directoryAtEndStaysThereAfterStateRestorationAndRepeatedSwipes() {
        val restoration = StateRestorationTester(compose)
        val data = page()
        lateinit var loading: MutableState<Boolean>
        restoration.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize()) {
            loading = remember { mutableStateOf(true) }
            NovelContentsBody(NovelContentsQuery("v", offset = 100), data.takeUnless { loading.value }, loading.value, null,
                emptySet(), "c145", 145, true, { _, _ -> }, {}, {})
        } } } }
        val list = compose.onNode(hasScrollToIndexAction())
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("正在阅读").assertIsDisplayed()
        list.performScrollToIndex(99)
        compose.onNodeWithText("第199 节 山海来信").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("第199 节 山海来信").assertIsDisplayed()
        repeat(3) { list.performTouchInput { swipeUp() } }
        compose.onNodeWithText("第199 节 山海来信").assertIsDisplayed()
        compose.onNodeWithText("当前章").performClick()
        compose.onNodeWithText("正在阅读").assertIsDisplayed()
    }

    @Test fun pageNavigationStartsAtTheFirstRowEvenOnTheCurrentChaptersPage() {
        var query by mutableStateOf(NovelContentsQuery("v", offset = 100))
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize()) {
            val data = remember(query) {
                page().put("offset", query.offset)
                    .put("items", JSONArray((query.offset until query.offset + query.limit).map { chapter(it) }))
            }
            NovelContentsBody(query, data, false, null, emptySet(), "c145", 145, true, { next, _ -> query = next }, {}, {})
        } } } }
        compose.onNodeWithText("正在阅读").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页目录").performClick()
        compose.onNodeWithContentDescription("上一页目录").performClick()
        compose.onNodeWithText("第100 节 山海来信").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        compose.onNodeWithText("2 / 24 页").performClick()
        compose.onNodeWithText("页码（1–24）").performTextInput("2")
        compose.onNodeWithText("跳转").performClick()
        compose.onNodeWithText("第100 节 山海来信").assertIsDisplayed()
    }

    @Test fun jumpUsesFilteredPagesAndExplicitEndDisablesNext() {
        var query by mutableStateOf(NovelContentsQuery("v", "山海"))
        val data = page().put("total", 124).put("next_offset", JSONObject.NULL)
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            NovelContentsBody(query, data, false, null, emptySet(), null, null, true, { next, _ -> query = next }, {}, {})
        } } } }
        compose.onNodeWithContentDescription("下一页目录").assertIsNotEnabled()
        compose.onNodeWithText("2 / 2 页").performClick()
        compose.onNodeWithText("页码（1–2）").performTextInput("3")
        compose.onNodeWithText("跳转").assertIsNotEnabled()
        compose.onNodeWithText("页码（1–2）").performTextClearance()
        compose.onNodeWithText("页码（1–2）").performTextInput("1")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(0, query.offset); assertEquals("山海", query.q) }
    }

    @Test fun offlineScopeAndVolumeHeadingsAreClearAndCannotOpen() {
        val data = page().put("_downloaded_only", true).put("latest", JSONObject.NULL).put("offset", 0)
            .put("items", JSONArray().put(chapter(101, false)))
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            NovelContentsBody(NovelContentsQuery("v"), data, false, null, emptySet(), null, null, true, { _, _ -> }, {}, { fail("Volume cannot open") })
        } } } }
        compose.onNodeWithText("搜索已下载章节标题").assertIsDisplayed()
        compose.onNodeWithText("第101 节 山海来信").assertIsNotEnabled()
        compose.onNodeWithText("读末章").assertDoesNotExist()
    }

    @Test fun sparseOfflineDirectoryLocatesTheCurrentChapterBeforeAndAfterPaging() {
        val repo = NovelRepository(compose.activity)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val cached = (0 until 230).map { chapter(it * 10) }
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize()) {
            NovelContentsPanel(repo, null, "book", "v", true, {}, Modifier.weight(1f),
                currentId = "c1450", currentOrdinal = 1450, downloadedChapters = { reads.incrementAndGet(); cached })
        } } } }
        fun awaitTitle(title: String) = compose.waitUntil(5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        awaitTitle("正在阅读")
        compose.onNodeWithText("2 / 3 页").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页目录").performClick()
        awaitTitle("第2000 节 山海来信")
        compose.onNodeWithText("3 / 3 页").assertIsDisplayed()
        compose.onNodeWithText("当前章").performClick()
        awaitTitle("正在阅读")
        compose.onNodeWithText("第1450 节 山海来信").assertIsDisplayed()
        assertEquals(1, reads.get())
    }

    @Test fun removedOfflineChapterStaysInPlaceAndCannotBeOpened() {
        val data = page().put("_downloaded_only", true)
        var downloaded by mutableStateOf((100..199).map { "c$it" }.toSet())
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize()) {
            NovelContentsBody(NovelContentsQuery("v", offset = 100), data, false, null,
                downloaded, "c145", 145, true, { _, _ -> }, {}, { fail("Missing download cannot open") })
        } } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        val last = compose.onNodeWithText("第199 节 山海来信")
        last.assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { downloaded -= "c199" }
        last.assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("本机缓存已移除").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w640dp-h360dp-mdpi") fun landscapeAndKeyboardHeightKeepPagingAndJumpReachable() {
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.NIGHT)) { Surface { Column(Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 20.dp)) {
            NovelContentsBody(NovelContentsQuery("v"), page(), false, null, emptySet(), "c145", 145, true, { _, _ -> }, {}, {})
        } } } }
        compose.onNodeWithContentDescription("下一页目录").assertIsDisplayed()
        compose.onNodeWithText("2 / 24 页").performClick()
        compose.onNodeWithText("页码（1–24）").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页目录").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起页码跳转").performClick()
        compose.onNodeWithText("搜索全书章节标题").assertIsDisplayed()
    }
}
