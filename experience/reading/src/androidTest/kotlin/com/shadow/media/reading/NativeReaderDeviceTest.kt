package com.shadow.media.reading

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.shadow.media.library.LibraryRepository
import com.shadow.media.library.ProgressWriter
import java.io.File

/** Runs the actual activity, Canvas pagination, cross-chapter navigation and ordered disk writer. */
@RunWith(AndroidJUnit4::class)
class NativeReaderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun reader(root: View): NativeTextView? = if (root is NativeTextView) root else
        (root as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { reader(group.getChildAt(it)) } }
    private fun waitFor(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            var passed = false
            instrumentation.runOnMainSync { passed = condition() }
            if (passed) return
            Thread.sleep(50)
        }
        error("Native reader did not reach expected state")
    }
    @Test fun txtTurnsAcrossChaptersReflowsAndRestoresCharacterProgress() = runBlocking {
        val context = instrumentation.targetContext
        val library = LibraryRepository(context)
        val file = File(context.cacheDir, "native-device.txt")
        file.writeText("第一章 山海夜读\n" + ("　　山间的雾气渐渐散去，竹叶上还挂着露珠。少年收起书卷，望向远处的灯火。\n“今日便出发吗？”她轻声问道。他点点头：“路虽远，总要一步一步走。”\n").repeat(25) +
            "第二章 星河\n" + "　　星光照亮了山路，溪水在石桥下缓缓流过。两人走过长亭，回首已经看不见旧日的村庄。\n".repeat(20))
        val id = LibraryRepository.digest("native-device-validation")
        library.dao.resetProgress(id)
        val asset = library.importPrepared(id, "local", "native-device-validation", "山海夜读.txt", "txt", file)
        fun launch() = instrumentation.startActivitySync(Intent(context, NativeTextReaderActivity::class.java).putExtra("assetId", asset.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeTextReaderActivity
        var activity = launch()
        var view: NativeTextView? = null
        try {
            waitFor { view = reader(activity.window.decorView); view?.document?.pages?.size?.let { it > 2 } == true }
            assertNull(library.dao.progress(id)) // Opening/layout is not a movement.
            instrumentation.waitForIdleSync()
            Thread.sleep(300) // Wait for the first compositor frame, after background pagination.
            instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(context.getExternalFilesDir(null), "native-reader-device.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            val down = android.os.SystemClock.uptimeMillis()
            val x = view!!.width * .85f; val y = view!!.height * .5f
            for (step in 0..8) {
                val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 8 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val event = android.view.MotionEvent.obtain(down, down + step * 30L, action, x - view!!.width * .7f * step / 8f, y, 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            waitFor { view!!.pageIndex == 1 }
            ProgressWriter.flush()
            val first = JSONObject(library.dao.progress(id)!!.locatorJson)
            val anchor = first.getJSONObject("native_text").getInt("offset")
            assertTrue(anchor > 0)
            val beforeReflow = library.dao.progress(id)!!.updatedAt
            instrumentation.runOnMainSync { view!!.configure(ReaderSettings(fontSize = 150f)) }
            waitFor { view!!.document?.settings?.fontSize == 150f }
            ProgressWriter.flush()
            assertEquals(anchor, view!!.readingOffset)
            assertEquals(beforeReflow, library.dao.progress(id)!!.updatedAt)
            instrumentation.runOnMainSync { view!!.seek(view!!.document!!.chapter.text.length); view!!.turn(1) }
            waitFor { view!!.document?.chapter?.ref?.id == "c1" }
            ProgressWriter.flush()
            assertEquals("c1.xhtml", JSONObject(library.dao.progress(id)!!.locatorJson).getString("href"))
            instrumentation.runOnMainSync { view!!.turn(-1) }
            waitFor { view!!.document?.chapter?.ref?.id == "c0" }
            assertEquals(view!!.document!!.pages.lastIndex, view!!.pageIndex)
            instrumentation.runOnMainSync { view!!.turn(-1) }
            waitFor { view!!.pageIndex == view!!.document!!.pages.lastIndex - 1 }
            ProgressWriter.flush()
            val expected = view!!.readingOffset
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            activity = launch()
            waitFor { view = reader(activity.window.decorView); view?.document != null }
            assertEquals(expected, view!!.readingOffset)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            ProgressWriter.flush()
            library.remove(id); file.delete()
        }
    }
}
