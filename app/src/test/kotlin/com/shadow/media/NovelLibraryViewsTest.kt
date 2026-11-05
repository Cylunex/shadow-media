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
    @Test fun shelfManagementSelectsBooksAndUsesExistingGroupWithoutOpeningReader() {
        var appliedIds: Set<String> = emptySet()
        var appliedAction = ""
        var appliedGroup = ""
        val data = JSONObject().put("total", 1).put("items", JSONArray().put(book("one", "山海来信")))
        val navigation = JSONObject().put("groups", JSONArray().put("追更"))
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage("shelf", NovelQuery(view = "shelf"), "", navigation, data, false,
            {}, {}, {}, {}, {}, { error("Management must not open details") }, { error("Management must not open reader") },
            onBulk = { ids, action, value, _ -> appliedIds = ids; appliedAction = action; appliedGroup = value }) } } }
        compose.onNodeWithText("山海来信").performTouchInput { longClick() }
        compose.onNodeWithText("整理 1 本").assertIsDisplayed()
        compose.onAllNodesWithText("追更", substring = false).onLast().performClick()
        compose.onNodeWithText("应用", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(setOf("one"), appliedIds)
        compose.onNodeWithText("管理", substring = false).performClick()
        compose.onNodeWithText("选择已显示").performClick()
        compose.onNodeWithText("整理 1 本").performClick()
        compose.onAllNodesWithText("追更", substring = false).onLast().performClick()
        compose.onNodeWithText("应用", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(setOf("one"), appliedIds)
        assertEquals("group", appliedAction)
        assertEquals("追更", appliedGroup)
    }

    @Test fun savedFilterRoundTripPreservesExclusionsGroupsAndRanges() {
        val query = NovelQuery(view = "shelf", q = "远航", genres = listOf("科幻"), excluded = listOf("恐怖"),
            personalTags = listOf("周末"), group = "常读", minWords = "300000", maxWords = "1000000", updatedDays = "7", hasUpdates = true)
        assertEquals(query, novelRestoreFilter(novelFilterSnapshot(query)))
    }

    @Test fun sourceGroupingKeepsUnknownAndDifferentAuthorsSeparate() {
        fun row(id: String, title: String, author: String) = JSONObject().put("provider_id", id).put("bookUrl", "/book").put("name", title).put("author", author)
        val groups = novelResultGroups(listOf(row("a", "远航", "行舟"), row("b", " 远航 ", "行舟"), row("c", "远航", "另一人"), row("d", "远航", "未知"), row("e", "远航", "未知")))
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.size })
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
        var detail: String? = null
        val data = JSONObject().put("total", 2).put("items", JSONArray().put(book("1", "山海来信")).put(book("2", "长夜与星河")))
        val navigation = JSONObject().put("counts", JSONObject().put("updates", 1))
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "深色").commit()
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage("shelf", query, search, navigation, data, false,
            onSearch = { search = it }, onSubmit = {}, onQuery = { query = it }, onFilter = {}, onMore = {}, onWork = { detail = it }, onRead = { read = it }) } } }
        compose.onNodeWithContentDescription("清除找书关键词").performClick()
        compose.runOnIdle { assertEquals("", query.q); assertEquals("", search) }
        compose.onNodeWithText("有更新 1").performClick()
        compose.runOnIdle { assertTrue(query.hasUpdates) }
        compose.onNodeWithContentDescription("移除有更新", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertFalse(query.hasUpdates) }
        compose.onNodeWithText("山海来信").performClick()
        assertEquals("1", read)
        assertNull(detail)
        compose.onNodeWithContentDescription("山海来信 · 更多操作").performClick()
        compose.onNodeWithText("详情").performClick()
        assertEquals("1", detail)
        screenshot("novel-shelf-dark")
        compose.onNodeWithContentDescription("切换列表视图").performClick()
        compose.onNodeWithContentDescription("切换网格视图").assertIsDisplayed()
        compose.onNodeWithText("沿着旧日留下的来信，寻找群山背后的灯火。").assertDoesNotExist()
        assertTrue(compose.onNodeWithText("山海来信").fetchSemanticsNode().boundsInRoot.height <= 120 * compose.activity.resources.displayMetrics.density)
        screenshot("novel-shelf-list")
        compose.runOnIdle { read = null; detail = null }
        compose.onNodeWithText("山海来信").performClick()
        assertEquals("1", read)
        assertNull(detail)
        compose.onNodeWithContentDescription("山海来信 · 更多操作").performClick()
        compose.onNodeWithText("详情").performClick()
        assertEquals("1", detail)
        compose.onNodeWithText("最近收录").performClick()
        compose.onNodeWithText("最近阅读").performClick()
        compose.runOnIdle { assertEquals("last_read", query.sort) }
        compose.onNodeWithText("最近阅读").performClick()
        compose.onNodeWithText("书名").performClick()
        compose.runOnIdle { assertEquals("title", query.sort) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun compactShelfControlsAreReachable() {
        val data = JSONObject().put("total", 1).put("items", JSONArray().put(book("1", "山海来信与远方灯火的漫长故事")))
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", "浅色").commit()
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage("shelf", NovelQuery(), "", JSONObject(), data, false,
            onSearch = {}, onSubmit = {}, onQuery = {}, onFilter = {}, onMore = {}, onWork = {}, onRead = {}) } } }
        compose.onNodeWithContentDescription("全部筛选").assertIsDisplayed()
        compose.onNodeWithContentDescription("山海来信与远方灯火的漫长故事 · 更多操作").assertIsDisplayed()
        screenshot("novel-shelf-compact")
    }

    @Test fun metadataOnlyShelfBookStillOpensDetails() {
        var read: String? = null
        var detail: String? = null
        val work = book("1", "仅资料作品").put("availability", JSONArray().put("metadata_only"))
        val data = JSONObject().put("total", 1).put("items", JSONArray().put(work))
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage("shelf", NovelQuery(view = "shelf"), "", JSONObject(), data, false,
            onSearch = {}, onSubmit = {}, onQuery = {}, onFilter = {}, onMore = {}, onWork = { detail = it }, onRead = { read = it }) } } }
        compose.onNodeWithText("仅资料作品").performClick()
        assertEquals("1", detail)
        assertNull(read)
    }

    @Test fun searchBookStillOpensDetailsAndHistoryBookOpensReader() {
        var view by mutableStateOf("search")
        var read: String? = null
        var detail: String? = null
        val data = JSONObject().put("total", 1).put("items", JSONArray().put(book("1", "山海来信")))
        compose.setContent { ShadowMediaTheme { Surface { NovelWorksPage(view, NovelQuery(view = view), "", JSONObject(), data, false,
            onSearch = {}, onSubmit = {}, onQuery = {}, onFilter = {}, onMore = {}, onWork = { detail = it }, onRead = { read = it }) } } }
        compose.onNodeWithText("山海来信").performClick()
        assertEquals("1", detail)
        assertNull(read)
        compose.runOnIdle { view = "history"; detail = null }
        compose.onNodeWithText("山海来信").performClick()
        assertEquals("1", read)
        assertNull(detail)
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
    @Test fun nextOfflineChapterSkipsVolumeAndNeedsExactPosition() {
        val directory = JSONObject("""{"items":[{"id":"a","readable":true},{"id":"volume","readable":false},{"id":"b","readable":true}]}""")
        assertEquals("下一章已缓存", novelNextOfflineStatus(directory, "a", setOf("b")))
        assertEquals("下一章未缓存", novelNextOfflineStatus(directory, "a", setOf("a")))
        assertEquals("已到目录末章", novelNextOfflineStatus(directory, "b", setOf("b")))
        assertEquals("离线位置待确认", novelNextOfflineStatus(directory, "missing", setOf("b")))
        assertEquals("离线目录待缓存", novelNextOfflineStatus(null, "a", setOf("b")))
    }

    @Test fun continuousOfflineRangeNeverSkipsMissingBodyAndIgnoresVolumes() {
        val directory = JSONObject("""{"items":[{"id":"a"},{"id":"volume","readable":false},{"id":"b"},{"id":"c"}]}""")
        assertEquals(2, novelContinuousOfflineCount(directory, "a", setOf("a", "b")))
        assertEquals(0, novelContinuousOfflineCount(directory, "a", setOf("b", "c")))
        assertEquals(2, novelContinuousOfflineCount(directory, "b", setOf("b", "c")))
        assertEquals(null, novelContinuousOfflineCount(directory, "missing", setOf("a", "b")))
        assertEquals(null, novelContinuousOfflineCount(null, "a", setOf("a")))
    }

}
