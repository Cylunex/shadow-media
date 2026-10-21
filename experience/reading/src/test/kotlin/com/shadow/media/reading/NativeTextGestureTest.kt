package com.shadow.media.reading

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeTextGestureTest {
    private fun reader(): NativeTextView {
        val view = NativeTextView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 432, 900)
        view.show(NativeTextChapter(NativeChapterRef("c0", "山海夜读", 0, "c0.xhtml"), "山间的雾气渐渐散去，少年翻开书页。\n".repeat(200), "v1", 1),
            "山海夜读", 0, ReaderSettings(pageMode = ReaderPageMode.NONE))
        val deadline = System.nanoTime() + 5_000_000_000
        while (view.document == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertNotNull("Background pagination must complete", view.document)
        assertTrue(view.document!!.pages.size > 2)
        return view
    }
    private fun gesture(view: NativeTextView, start: Float, end: Float, cancel: Boolean = false) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val x = if (i == 0) start else end
            MotionEvent.obtain(time, time + i * 60, action, x, 450f, 0).also { view.onTouchEvent(it); it.recycle() }
        }
    }
    @Test fun realTouchStreamTurnsOnePageAndNeverOpensTheCenterMenu() {
        val view = reader()
        try {
            var menus = 0; view.onMenu = { menus++ }
            gesture(view, 380f, 100f)
            assertEquals(1, view.pageIndex)
            assertEquals(0, menus)
            assertTrue(view.readingOffset > 0)
        } finally { view.dispose() }
    }
    @Test fun cancelledDragAndMenuOverlayCannotTurnUnderlyingPages() {
        val view = reader()
        try {
            gesture(view, 380f, 100f, cancel = true)
            assertEquals(0, view.pageIndex)
            view.gesturesEnabled = false
            gesture(view, 380f, 100f)
            assertEquals(0, view.pageIndex)
            view.gesturesEnabled = true
            gesture(view, 380f, 100f)
            assertEquals(1, view.pageIndex)
        } finally { view.dispose() }
    }
    @Test fun centerTapOpensControlsOnceAndDoesNotChangeProgress() {
        val view = reader()
        try {
            var menus = 0; view.onMenu = { menus++ }
            gesture(view, 216f, 216f)
            assertEquals(1, menus)
            assertEquals(0, view.pageIndex)
            assertEquals(0, view.readingOffset)
        } finally { view.dispose() }
    }
}
