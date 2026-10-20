@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.cylunex.shadowmedia.reading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Theme
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderPreferencesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun oldFontThemeAndScrollPreferencesMigrate() {
        val name = "reader-test-${UUID.randomUUID()}"
        val prefs = instrumentation.targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().putFloat("font", 145f).putFloat("line", 2f).putString("theme", "DARK").putBoolean("scroll", true).commit()
            val value = ReaderSettingsStore(prefs).load()
            assertEquals(145f, value.fontSize); assertEquals(2f, value.lineHeight)
            assertEquals(ReaderPaper.NIGHT, value.paper); assertEquals(ReaderPageMode.SCROLL, value.pageMode)
        } finally { instrumentation.targetContext.deleteSharedPreferences(name) }
    }

    @Test fun everyPreferenceSurvivesReopeningAndUnknownEnumsUseDefaults() {
        val name = "reader-test-${UUID.randomUUID()}"
        val prefs = instrumentation.targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val value = ReaderSettings(fontSize = 160f, lineHeight = 2.2f, paragraphSpacing = 1.3f, letterSpacing = 0.05f,
                margins = 1.5f, font = ReaderFont.MONO, bold = true, indent = false, paper = ReaderPaper.GREEN,
                pageMode = ReaderPageMode.CURL, fullScreen = false, tapToTurn = false, volumeToTurn = true,
                brightnessGesture = false, pinchToResize = false, keepAwake = false, brightness = 0.3f)
            ReaderSettingsStore(prefs).save(value)
            assertEquals(value, ReaderSettingsStore(prefs).load())
            prefs.edit().putString("paper", "future-paper").putString("pageMode", "future-mode").commit()
            assertEquals(ReaderPaper.PARCHMENT, ReaderSettingsStore(prefs).load().paper)
            assertEquals(ReaderPageMode.SLIDE, ReaderSettingsStore(prefs).load().pageMode)
        } finally { instrumentation.targetContext.deleteSharedPreferences(name) }
    }

    @Test fun allPageModesAndPapersMapIntoReadiumWithoutResettingTypography() {
        for (mode in ReaderPageMode.entries) for (paper in ReaderPaper.entries) {
            val prefs = ReaderSettings(fontSize = 150f, pageMode = mode, paper = paper, bold = true).epubPreferences()
            assertEquals(mode == ReaderPageMode.SCROLL, prefs.scroll)
            assertEquals(paper.background, prefs.backgroundColor!!.int)
            assertEquals(paper.foreground, prefs.textColor!!.int)
            assertEquals(150.0, prefs.fontSize!!, 0.0)
            assertEquals(700.0, prefs.fontWeight!!, 0.0)
            assertEquals(if (paper == ReaderPaper.NIGHT) Theme.DARK else Theme.LIGHT, prefs.theme)
        }
    }

    @Test fun foldDrawsBothDirectionsAndReleasesSnapshotOnCancellation() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val content = View(context).apply { setBackgroundColor(ReaderPaper.PARCHMENT.background); layout(0, 0, 400, 600) }
            val overlay = PageTurnOverlay(context).apply { layout(0, 0, 400, 600) }
            val output = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            try {
                for (mode in listOf(ReaderPageMode.CURL, ReaderPageMode.SLIDE, ReaderPageMode.COVER)) for (forward in listOf(true, false)) {
                    overlay.begin(content, mode, forward, ReaderPaper.PARCHMENT.background)
                    assertTrue(overlay.hasPage)
                    overlay.drag(0.4f)
                    overlay.draw(Canvas(output))
                    overlay.clear()
                    assertFalse(overlay.hasPage); assertFalse(overlay.busy)
                }
                overlay.begin(content, ReaderPageMode.NONE, true, ReaderPaper.WHITE.background)
                assertFalse(overlay.hasPage)
            } finally { overlay.clear(); output.recycle() }
        }
    }
}
