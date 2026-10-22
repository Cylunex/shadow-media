package com.shadow.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.shadow.media.library.NovelQuery
import com.shadow.media.ui.theme.ShadowMediaTheme
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
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelLibraryViewsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun book(id: String, title: String) = JSONObject().put("id", id).put("title", title).put("author", "行舟")
        .put("description", "沿着旧日留下的来信，寻找群山背后的灯火。").put("serial_status", "ongoing")
        .put("availability", JSONArray().put("online")).put("update_count", 8).put("progress", JSONObject().put("ratio", .42))
    private fun screenshot(name: String) {
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { folder ->
            val bitmap = compose.runOnIdle { val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
            File(folder).mkdirs()
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun homeHasRealSearchShortcutsAndChapterScopedContinueProgress() {
        var search by mutableStateOf("")
        var submitted = ""
        var shortcut = ""
        var read = false
        val work = book("1", "山海来信")
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "浅色").commit()
        compose.setContent { ShadowMediaTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NovelHomeHeader(128, search, { search = it }, true, { submitted = search }, { shortcut = it })
            NovelContinueCard(work, true, { read = true }, {})
            Text("热门作品", style = MaterialTheme.typography.titleLarge)
            NovelBookStrip(listOf(work, book("2", "长夜与星河"), book("3", "远山灯火")), true, {})
        } } } }
        compose.onNodeWithText("搜索书名、作者或题材").performTextInput("星河")
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals("星河", submitted)
        compose.onNodeWithText("完本").performClick()
        assertEquals("completed", shortcut)
        compose.onNodeWithText("本章已读 42%").assertIsDisplayed()
        compose.onNodeWithText("继续阅读").performClick()
        assertTrue(read)
        screenshot("novel-home-light")
    }

    @Test fun shelfChangesServerFilterAndViewWithoutOpeningBookAccidentally() {
        var query by mutableStateOf(NovelQuery(view = "shelf", q = "山海"))
        var search by mutableStateOf("山海")
        var read: String? = null
        val data = JSONObject().put("total", 2).put("items", JSONArray().put(book("1", "山海来信")).put(book("2", "长夜与星河")))
        val navigation = JSONObject().put("counts", JSONObject().put("updates", 1))
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "深色").commit()
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage("shelf", query, search, navigation, data, false,
            onSearch = { search = it }, onSubmit = {}, onQuery = { query = it }, onFilter = {}, onMore = {}, onWork = {}, onRead = { read = it }) } } }
        compose.onNodeWithContentDescription("清除找书关键词").performClick()
        compose.runOnIdle { assertEquals("", query.q); assertEquals("", search) }
        compose.onNodeWithText("有更新 1").performClick()
        compose.runOnIdle { assertTrue(query.hasUpdates) }
        compose.onNodeWithContentDescription("移除有更新", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertFalse(query.hasUpdates) }
        compose.onAllNodesWithText("继续阅读").onFirst().performClick()
        assertEquals("1", read)
        screenshot("novel-shelf-dark")
        compose.onNodeWithContentDescription("切换列表视图").performClick()
        compose.onNodeWithContentDescription("切换网格视图").assertIsDisplayed()
        compose.onNodeWithText("最近收录").performClick()
        compose.onNodeWithText("书名").performClick()
        compose.runOnIdle { assertEquals("title", query.sort) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun compactShelfControlsAreReachable() {
        val data = JSONObject().put("total", 1).put("items", JSONArray().put(book("1", "山海来信与远方灯火的漫长故事")))
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "浅色").commit()
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage("shelf", NovelQuery(), "", JSONObject(), data, false,
            onSearch = {}, onSubmit = {}, onQuery = {}, onFilter = {}, onMore = {}, onWork = {}, onRead = {}) } } }
        compose.onNodeWithContentDescription("全部筛选").assertIsDisplayed()
        compose.onNodeWithText("继续阅读").assertIsDisplayed()
        screenshot("novel-shelf-compact")
    }

    @Test fun removingOneFilterPreservesOtherDimensionsAndMetadataOnlyCannotRead() {
        val query = NovelQuery(view = "shelf", q = "星河", categories = listOf("sci-fi"), genres = listOf("群像"), hasUpdates = true, group = "睡前")
        val cleared = novelFilterLabels(query, JSONObject()).single { it.first == "群像" }.second
        assertTrue(cleared.genres.isEmpty())
        assertEquals("星河", cleared.q)
        assertEquals(listOf("sci-fi"), cleared.categories)
        assertTrue(cleared.hasUpdates)
        assertEquals("睡前", cleared.group)
        assertFalse(novelCanRead(JSONObject().put("availability", JSONArray().put("metadata_only"))))
        assertTrue(novelCanRead(JSONObject().put("availability", JSONArray().put("cached"))))
    }
}
