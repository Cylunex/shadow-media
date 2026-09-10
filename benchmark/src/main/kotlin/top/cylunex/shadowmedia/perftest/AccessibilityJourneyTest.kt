package top.cylunex.shadowmedia.perftest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityJourneyTest {
    @Before fun seed() = Journeys.seed()
    @Test fun largeTextKeepsCoreActionsReachableWithFortyEightDpTargets() {
        val device = Journeys.device
        val original = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 2.0")
            Journeys.launch(); Journeys.music()
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            for (label in listOf("导入歌曲", "音乐目录", "播放当前列表")) {
                var target = Journeys.require(By.text(label))
                while (!target.isClickable && target.parent != null) target = target.parent
                val bounds = target.visibleBounds
                assertTrue("$label is not actionable", target.isClickable && target.isEnabled)
                assertTrue("$label is shorter than 48dp", bounds.height() / density >= 47.5f)
                assertTrue("$label is narrower than 48dp", bounds.width() / density >= 47.5f)
            }
            Journeys.click("导入歌曲")
            assertTrue("System document picker did not open", device.wait(Until.gone(By.res("music-list")), 5000))
        } finally {
            device.executeShellCommand(if (original == "null") "settings delete system font_scale" else "settings put system font_scale $original")
        }
    }
    @Test fun readerKeepsPublicationAcrossRotation() {
        try {
            Journeys.launch(); Journeys.reader()
            Journeys.device.setOrientationLeft()
            Journeys.clickDescription("目录"); Journeys.require(By.text("Chapter 2")).click()
            Journeys.device.setOrientationNatural()
            Journeys.require(By.desc("全文搜索")); Journeys.clickDescription("目录")
            Journeys.require(By.text("Chapter 2"))
        } finally { Journeys.device.unfreezeRotation() }
    }
}
