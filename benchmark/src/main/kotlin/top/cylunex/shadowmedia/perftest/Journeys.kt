package top.cylunex.shadowmedia.perftest

import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.assertTrue

internal const val TARGET = "top.cylunex.shadowmedia.benchmark"
internal object Journeys {
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun launch(activity: String = "MainActivity") {
        InstrumentationRegistry.getInstrumentation().context.startActivity(Intent().apply {
            component = ComponentName(TARGET, "top.cylunex.shadowmedia.$activity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
    }
    fun seed() { launch("BenchmarkSetupActivity"); require(By.desc("fixture-ready"), 90_000); device.pressHome() }
    fun require(selector: BySelector, timeout: Long = 15_000): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Required UI missing: $selector" }
    fun click(text: String) { require(By.text(text)).click(); device.waitForIdle() }
    fun home() { require(By.desc("影视")); device.waitForIdle() }
    fun music() { require(By.desc("音频")).click(); click("音乐"); require(By.textStartsWith("Fixture Song")) }
    fun scrollSongs() {
        music()
        val list = require(By.res("music-list"))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(4) { list.scroll(Direction.DOWN, 0.8f) }
        assertTrue("Songs must remain visible after paging", device.hasObject(By.textStartsWith("Fixture Song")))
    }
    fun videoWall() { click("媒体库"); click("Fixture Movies"); require(By.textStartsWith("Fixture Video")) }
    fun video() { videoWall(); require(By.textStartsWith("Fixture Video")).click(); require(By.text("播放诊断")) }
    fun reader() {
        require(By.desc("阅读")).click(); click("Fixture EPUB")
        require(By.desc("全文搜索")); clickDescription("目录")
        // These are publication TOC entries, so this fails if only the activity chrome opened.
        require(By.text("Chapter 1")); device.pressBack()
    }
    fun audio() { music(); require(By.textStartsWith("Fixture Song")).click(); require(By.desc("暂停")) }
    fun switchAccount() {
        click("服务器"); require(By.textContains("Fixture Second")).click()
        require(By.textContains("Fixture Second")); require(By.text("媒体库"))
    }
    fun clickDescription(description: String) { require(By.desc(description)).click(); device.waitForIdle() }
}
