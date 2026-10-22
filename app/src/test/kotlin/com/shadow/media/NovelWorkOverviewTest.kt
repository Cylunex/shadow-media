package com.shadow.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.shadow.media.ui.theme.ShadowMediaTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelWorkOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun primaryReadActionStaysAboveLongDescriptionAndIntroductionCanExpand() {
        val work = JSONObject().put("id", "work").put("title", "山海来信").put("author", "行舟")
            .put("category", "奇幻").put("serial_status", "ongoing").put("availability", JSONArray().put("readable"))
            .put("versions", JSONArray().put(JSONObject().put("id", "v1")))
            .put("genre_tags", JSONArray().put("成长").put("冒险"))
            .put("description", "少年带着一封来信踏上旅途，在山海间寻找故乡的答案。".repeat(25))
        var read = false
        var search = false
        compose.setContent { ShadowMediaTheme { Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            NovelWorkOverview(work, true, true, onRead = { read = true }, onAuthor = {}, onSearch = { search = true })
        } } } }
        compose.onNodeWithText("开始阅读").assertIsDisplayed().performClick()
        compose.onNodeWithText("找书源").assertIsDisplayed().performClick()
        assertTrue(read)
        assertTrue(search)
        compose.onNodeWithText("展开简介").performScrollTo().performClick()
        compose.onNodeWithText("收起简介").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("山海来信"))
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { folder ->
            val bitmap = compose.runOnIdle {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            File(folder).mkdirs()
            File(folder, "novel-work-overview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun metadataOnlyWorkDisablesReadAndKeepsSourceSearchAvailable() {
        val work = JSONObject().put("id", "metadata").put("title", "山海来信").put("availability", JSONArray().put("metadata_only"))
        var searched = false
        compose.setContent { ShadowMediaTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            NovelWorkOverview(work, true, true, onRead = { fail("No readable version") }, onAuthor = {}, onSearch = { searched = true })
        } } } }
        compose.onNodeWithText("暂无可读版本").assertIsNotEnabled()
        compose.onNodeWithText("找书源").performClick()
        assertTrue(searched)
    }
}
