package com.shadow.media.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import com.shadow.media.database.ContentAnnotationEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.shadow.media.library.LibraryRepository
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeReaderUiTest {
    @Test fun typographyPresetsPreviewAndFineControlsAreReachableWithoutResettingPaper() {
        var style by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(style.paper)) { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("排版与纸张", style = MaterialTheme.typography.titleLarge)
            ReaderPreferencesPanel(style, { style = it }, nativeText = true)
        } } } }
        compose.onNodeWithContentDescription("阅读排版预览").assertIsDisplayed()
        screenshot("reader-typography-parchment")
        compose.onNodeWithText("舒展").performClick()
        compose.runOnIdle { assertEquals(120f, style.fontSize); assertEquals(ReaderPaper.PARCHMENT, style.paper) }
        compose.onNodeWithContentDescription("增大字号").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(125f, style.fontSize) }
        compose.onNodeWithText("纸张纹理").performScrollTo().assertIsDisplayed()
        screenshot("reader-paper-settings")
        compose.onNodeWithText("精细排版：字体、行距与页边距").performScrollTo().performClick()
        compose.onNodeWithText("正文两端对齐").performScrollTo().performClick()
        compose.onNodeWithText("宽屏限制行长").performScrollTo().performClick()
        compose.onNodeWithText("显示页眉与本章进度").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(style.justify); assertFalse(style.comfortableWidth); assertFalse(style.showProgress) }
        compose.onNodeWithText("恢复默认排版").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(ReaderTypographyPreset.CLASSIC.matches(style)); assertTrue(style.showProgress) }
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chapters = (100 until 200).map { NativeChapterRef("c$it", "山间来信与远方的灯火 $it", it, "chapters/c$it.xhtml") }
    private fun screenshot(name: String) {
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { folder ->
            File(folder).mkdirs()
            val bitmap = compose.runOnIdle {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun directoryLocatesCurrentChapterFiltersClearsAndUsesKeyboardForJump() {
        var requested by mutableIntStateOf(145)
        var opened: NativeChapterRef? = null
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("目录", style = MaterialTheme.typography.titleLarge)
            NativeContentsBody(chapters, setOf("c145", "c146"), "c145", 145, requested, 5000, 100, false, null, true,
                onRequest = { requested = it }, onRetry = {}, onOpen = { opened = it })
        } } } }
        compose.onNodeWithText(chapters[45].title).assertIsDisplayed()
        compose.onNodeWithText("正在阅读").assertIsDisplayed()
        screenshot("reader-directory")
        compose.onNodeWithText("筛选本页标题").performTextInput("146")
        compose.onNodeWithText(chapters[46].title).assertIsDisplayed().performClick()
        assertEquals("c146", opened?.id)
        compose.onNodeWithContentDescription("清除目录筛选").performClick()
        compose.onNodeWithText("当前章").performClick()
        compose.onNodeWithText(chapters[45].title).assertIsDisplayed()
        compose.onNodeWithText("跳章").performClick()
        compose.onNodeWithText("目录序号").performTextInput("0")
        compose.onNodeWithText("定位").assertIsNotEnabled()
        compose.onNodeWithText("目录序号").performTextClearance()
        compose.onNodeWithText("目录序号").performTextInput("321")
        compose.onNodeWithText("目录序号").performImeAction()
        compose.runOnIdle { assertEquals(320, requested) }
        compose.onNodeWithText("目录序号").assertDoesNotExist()
    }

    @Test fun searchSupportsImeClearAndClickingWholeResult() {
        var query by mutableStateOf("")
        var searched = false
        var opened: NativeSearchHit? = null
        val hit = NativeSearchHit("asset", "第一百四十六章 山间来信", "{}", "她收起那封来信，望向远方的灯火，山风吹散了昨日的阴霾。")
        var result by mutableStateOf<NativeSearchResult?>(null)
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("搜索", style = MaterialTheme.typography.titleLarge)
            NativeSearchBody(query, result, false, null, true, true,
                onQuery = { query = it; result = null }, onSearch = { searched = true; result = NativeSearchResult(listOf(hit), 24, 24, complete = true) },
                onStop = {}, onOpen = { opened = it })
        } } } }
        compose.onNodeWithText("查找故事里的细节").assertIsDisplayed()
        compose.onNodeWithText("搜索正文内容").performTextInput("来信")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertTrue(searched) }
        compose.onNodeWithText(hit.preview).assertIsDisplayed().performClick()
        assertEquals(hit, opened)
        screenshot("reader-search")
        compose.onNodeWithContentDescription("清除搜索关键词").performClick()
        compose.onNodeWithText("查找故事里的细节").assertIsDisplayed()
    }

    @Test fun jumpFieldAndPagingStayReachableWhenTheKeyboardLeavesLittleHeight() {
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 20.dp)) {
            NativeContentsBody(chapters, emptySet(), "c145", 145, 145, 5000, 100, false, null, true,
                onRequest = {}, onRetry = {}, onOpen = {})
        } } } }
        compose.onNodeWithText("跳章").performClick()
        compose.onNodeWithText("目录序号").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页目录").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起跳章").performClick()
        compose.onNodeWithText("筛选本页标题").assertIsDisplayed()
    }

    @Test fun compactControlsKeepToolsReachableAndSeekOnlyOnceAfterDragging() {
        var seeks = 0
        var panel: String? = null
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.width(360.dp).height(180.dp)) {
            NativeReaderControls("第一百四十六章", "12 / 36 页", .3f, true, false, true,
                onReturn = {}, onSeek = { seeks++ }, onPrevious = {}, onNext = {}, onSpeak = {}, onPanel = { panel = it }, compact = true)
        } } } }
        compose.onNodeWithText("目录").assertIsDisplayed().performClick()
        assertEquals("目录", panel)
        compose.onNodeWithContentDescription("返回跳转前的位置").assertIsDisplayed()
        screenshot("reader-compact-controls")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput { swipe(Offset(width * .3f, center.y), Offset(width * .8f, center.y), durationMillis = 400) }
        compose.runOnIdle { assertEquals(1, seeks) }
    }

    @Test fun bookmarkRowsOfferSeparateOpenAndDeleteTargetsAndAnEmptyState() {
        val mark = ContentAnnotationEntity("mark", "asset", "{}", "山风吹散了昨日的阴霾。", 0)
        var marks by mutableStateOf(listOf(mark))
        var opened = false
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            NativeReaderBookmarks(marks, true, onOpen = { opened = true }, onDelete = { marks = emptyList() })
        } } } }
        compose.onNodeWithText(mark.note).performClick()
        assertTrue(opened)
        compose.onNodeWithContentDescription("删除书签").performClick()
        compose.onNodeWithText("还没有书签").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun narrowPortraitMenuKeepsActionsVisibleAndClosesWithoutExitingReading() {
        var menu by mutableStateOf(true)
        var paper by mutableStateOf(ReaderPaper.IVORY)
        var exited = false
        var bookmarks = 0
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(paper)) { Surface { Box(Modifier.width(320.dp).fillMaxHeight()) {
            Text("山海来信 · 正文", Modifier.align(Alignment.Center))
            if (menu) {
                Box(Modifier.align(Alignment.TopCenter)) {
                    NativeReaderHeader("山海来信与远方灯火的漫长故事", "第一百四十六章 山间来信", "进度已保存在本机，等待同步", true,
                        onBack = { exited = true }, onClose = { menu = false }, onBookmark = { bookmarks++ },
                        night = paper == ReaderPaper.NIGHT, onToggleNight = { paper = if (paper == ReaderPaper.NIGHT) ReaderPaper.IVORY else ReaderPaper.NIGHT })
                }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    NativeReaderControls("第一百四十六章 山间来信", "12 / 36 页", .3f, true, true, true,
                        onReturn = {}, onSeek = {}, onPrevious = {}, onNext = {}, onSpeak = {}, onPanel = {})
                }
            }
        } } } }
        compose.onNodeWithText("下一页").assertIsDisplayed()
        compose.onNodeWithText("停止朗读").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加书签").performClick()
        assertEquals(1, bookmarks)
        screenshot("reader-menu-portrait")
        compose.onNodeWithContentDescription("切换夜间阅读").assertIsDisplayed().performClick()
        screenshot("reader-menu-night")
        compose.onNodeWithContentDescription("恢复日间纸张").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ReaderPaper.IVORY, paper) }
        compose.onNodeWithContentDescription("收起阅读菜单").performClick()
        compose.onNodeWithText("山海来信 · 正文").assertIsDisplayed()
        assertFalse(exited)
    }

    @Test fun bookmarkDeletionCanBeUndoneThroughTheVisibleSnackbar() {
        val library = LibraryRepository(RuntimeEnvironment.getApplication())
        val mark = ContentAnnotationEntity("undo", "asset", "{}", "山海之间的一封信", 0)
        runBlocking { library.dao.putAnnotation(mark) }
        compose.setContent { MaterialTheme(colorScheme = readerColorScheme(ReaderPaper.IVORY)) { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            val marks by library.dao.annotationsForAssets(listOf("asset")).collectAsState(emptyList())
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            NativeReaderBookmarks(marks, true, onOpen = {}, onDelete = { item -> scope.launch { deleteReaderBookmark(library, item, snackbar) } })
            SnackbarHost(snackbar)
        } } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(mark.note).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("删除书签").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(mark.note).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(mark, runBlocking { library.dao.annotationsForAssets(listOf("asset")).first().single() })
    }

    @Test fun allPaperPalettesKeepNormalAndSecondaryTextReadable() {
        for (paper in ReaderPaper.entries) {
            val scheme = readerColorScheme(paper)
            for ((text, background) in listOf(scheme.onSurface to scheme.surface, scheme.onSurfaceVariant to scheme.surface, scheme.onSecondaryContainer to scheme.secondaryContainer)) {
                val light = maxOf(text.luminance(), background.luminance())
                val dark = minOf(text.luminance(), background.luminance())
                assertTrue("$paper text contrast", (light + .05f) / (dark + .05f) >= 4.5f)
            }
        }
    }
}
