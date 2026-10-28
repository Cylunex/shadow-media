package com.shadow.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaNavigationChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun screenshot(name: String) {
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { directory ->
            val bitmap = compose.runOnIdle { val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun showShelf(theme: String) {
        compose.activity.getSharedPreferences("appearance", 0).edit().putString("theme", theme).commit()
        val books = JSONArray()
        listOf("山海来信", "长夜与星河", "远山灯火", "群山之间", "听风经过", "春日手记", "时间的形状", "人间漫游", "最后一班列车").forEachIndexed { index, title ->
            books.put(JSONObject().put("id", "$index").put("title", title).put("author", "行舟").put("serial_status", "ongoing"))
        }
        val data = JSONObject().put("total", books.length()).put("items", books)
        compose.setContent { ShadowMediaTheme {
            Scaffold(topBar = { MediaModuleHeader(MediaModule.NOVEL, {}, {}, {}) },
                bottomBar = { MediaModuleNavigation(MediaModule.NOVEL, MediaDestination.SHELF, {}) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    NovelWorksPage("shelf", NovelQuery(view = "shelf"), "", JSONObject(), data, false,
                        onSearch = {}, onSubmit = {}, onQuery = {}, onFilter = {}, onMore = {}, onWork = {}, onRead = {})
                }
            }
        } }
    }
    @Test fun darkShelfUsesNativeContentInsideModuleChrome() {
        showShelf("深色")
        compose.onNodeWithText("山海来信").assertIsDisplayed()
        compose.onAllNodes(tabs).assertCountEquals(4)
        screenshot("media-novel-dark-393")
        compose.onNodeWithTag("module-switch").performClick()
        compose.onNodeWithTag("module-COMIC").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun narrowLightShelfKeepsSearchFiltersAndBottomTargetsReachable() {
        showShelf("浅色")
        compose.onNodeWithContentDescription("全部筛选").assertIsDisplayed()
        compose.onNodeWithTag("destination-BOOK_SOURCES").assertIsDisplayed()
        screenshot("media-novel-light-320")
    }
    @Test fun switchingModuleReplacesMenusAndRemovesSinglePageNavigation() {
        var module by mutableStateOf(MediaModule.NOVEL)
        var destination by mutableStateOf(MediaDestination.SHELF)
        var settings = false
        compose.setContent { ShadowMediaTheme {
            Scaffold(topBar = { MediaModuleHeader(module, { module = it; destination = it.destinations.first() }, {}, { settings = true }) },
                bottomBar = { MediaModuleNavigation(module, destination, { destination = it }) }) { padding ->
                Text("当前内容：${destination.title}", Modifier.padding(padding))
            }
        } }
        compose.onNodeWithTag("destination-BOOK_SOURCES").performClick()
        compose.onNodeWithTag("destination-BOOK_SOURCES").assertIsSelected()
        compose.onNodeWithTag("module-switch").performClick()
        compose.onNodeWithTag("module-MUSIC").performClick()
        compose.onAllNodes(tabs).assertCountEquals(2)
        compose.onNodeWithTag("destination-BOOK_SOURCES").assertDoesNotExist()
        compose.onNodeWithTag("destination-PLAYLISTS").performClick()
        compose.onNodeWithText("当前内容：歌单").assertIsDisplayed()
        compose.onNodeWithTag("module-switch").performClick()
        compose.onNodeWithTag("module-LIVE").performClick()
        compose.onNodeWithTag("module-navigation").assertDoesNotExist()
        compose.onNodeWithText("当前内容：频道").assertIsDisplayed()
        compose.onNodeWithTag("module-switch").performClick()
        compose.onNodeWithText("通用设置").performClick()
        assertTrue(settings)
    }
}
