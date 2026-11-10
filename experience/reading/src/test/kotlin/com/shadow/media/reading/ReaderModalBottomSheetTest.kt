@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media.reading

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderModalBottomSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sheetRemainsStableThroughListUpdatesScrollingAndInsets() = checkSheet(.92f)

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun landscapeSearchSheetRemainsStableWithKeyboardInsets() = checkSheet(.95f)

    @Test fun typographySheetRemainsStableWithShorterHeight() = checkSheet(.58f)

    @Test fun rankingsSheetRemainsStableWithLongLists() = checkSheet(.85f)

    private fun checkSheet(fraction: Float) {
        var count by mutableIntStateOf(30)
        var sheetView: View? = null
        compose.setContent { MaterialTheme {
            ReaderModalBottomSheet(onDismissRequest = {}, heightFraction = fraction, sheetGesturesEnabled = false, dragHandle = null) {
                sheetView = LocalView.current
                Column(Modifier.fillMaxSize()) {
                    Text("书源", Modifier.testTag("heading").padding(16.dp))
                    OutlinedTextField("龙门", {}, Modifier.fillMaxWidth().testTag("query"))
                    LazyColumn(Modifier.weight(1f).testTag("sources")) {
                        items((0 until count).toList(), key = { it }) { Text("来源 $it", Modifier.fillMaxWidth().padding(20.dp)) }
                    }
                }
            }
        } }
        compose.waitForIdle()
        fun stableHeading() {
            val initial = compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInWindow
            assertTrue(initial.top >= 0f)
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals(initial, compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInWindow)
            }
        }
        stableHeading()
        compose.runOnIdle { count = 100 }
        compose.waitForIdle()
        stableHeading()
        compose.onNodeWithTag("sources").performScrollToNode(hasText("来源 90"))
        compose.onNodeWithText("来源 90").assertIsDisplayed()
        stableHeading()
        compose.onNodeWithTag("query").performClick()
        for (ime in listOf(280, 0)) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(sheetView), WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0).build())
            }
            compose.waitForIdle()
            stableHeading()
        }
    }
}
