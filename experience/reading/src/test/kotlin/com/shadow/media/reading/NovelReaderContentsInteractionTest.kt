package com.shadow.media.reading

import android.content.Intent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.LibraryRepository
import com.shadow.media.library.NovelRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelReaderContentsInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun swipingAtEndAndRecreatingReaderKeepLastChapterInPlace() {
        val context = RuntimeEnvironment.getApplication()
        val library = LibraryRepository(context)
        val novel = NovelRepository(context, library)
        val rows = (0 until 32).map { index -> JSONObject()
            .put("id", "c$index").put("title", "章节 $index").put("ordinal", index)
            .put("href", "chapters/c$index.xhtml") }
        val mapping = JSONObject().put("schema", "shadow.novel.publication/v1").put("work_id", "book")
            .put("version_id", "v1").put("complete", true).put("version_complete", true)
            .put("total", rows.size).put("chapters", JSONArray(rows))
        val file = File(context.cacheDir, "contents-test.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, value: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """<container><rootfile full-path="book.opf"/></container>""")
            entry("book.opf", "<package><metadata><title>测试书</title></metadata></package>")
            rows.forEach { entry(it.getString("href"), "<html><body><p>测试正文。</p></body></html>") }
        }
        val asset = runBlocking {
            library.importPrepared(LibraryRepository.digest("contents-test"), "novel:test", "book", "测试书", "epub", file).also {
                novel.dao.putPublication(NovelPublicationEntity(it.id, it.providerId, "book", "v1", mapping.toString(), it.revision))
            }
        }
        val intent = Intent(context, NativeTextReaderActivity::class.java).putExtra("assetId", asset.id)
        ActivityScenario.launch<NativeTextReaderActivity>(intent).use { scenario ->
            compose.waitUntil(10_000) {
                var opened = false
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView as ViewGroup
                    val view = decor.descendants.filterIsInstance<NativeTextView>().firstOrNull()
                    if (view?.document != null && view.isEnabled && view.gesturesEnabled) {
                        opened = view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                    }
                }
                opened
            }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("目录").assertIsEnabled().performClick()
            fun awaitRow(title: String) = compose.waitUntil(10_000) {
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            awaitRow("章节 1")
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToIndex(rows.lastIndex)
            val last = compose.onNodeWithText("章节 31")
            val end = last.assertIsDisplayed().getUnclippedBoundsInRoot()
            repeat(3) {
                list.performTouchInput { swipeUp() }
                assertEquals(end, last.assertIsDisplayed().getUnclippedBoundsInRoot())
            }
            // The first-use hint expires while the directory is open.
            compose.mainClock.advanceTimeBy(6_000)
            assertEquals(end, last.assertIsDisplayed().getUnclippedBoundsInRoot())
            scenario.recreate()
            awaitRow("章节 31")
            last.assertIsDisplayed()
            compose.onNodeWithText("当前章").performClick()
            compose.onNodeWithText("正在阅读").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭面板").performClick()
            compose.onNodeWithText("正在阅读").assertDoesNotExist()
            runBlocking { assertFalse(novel.dao.state(NovelRepository.stateId("novel:test", "book"))!!.pending) }
        }
    }
}
