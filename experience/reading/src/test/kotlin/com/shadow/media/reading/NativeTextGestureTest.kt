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
    @Test fun changingPaperRefreshesExistingPageWithoutRepaginationOrLosingTheAnchor() {
        val view = reader()
        try {
            view.seek(view.document!!.pages[2].start + 5)
            val before = view.document
            val offset = view.readingOffset
            val page = view.pageIndex
            var positions = 0; view.onPosition = { _, _ -> positions++ }
            fun snapshot() = android.graphics.Bitmap.createBitmap(432, 900, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            val parchment = snapshot()
            view.configure(ReaderSettings(pageMode = ReaderPageMode.NONE, paper = ReaderPaper.NIGHT))
            val night = snapshot()
            view.configure(ReaderSettings(pageMode = ReaderPageMode.NONE, paperTexture = 0f))
            val plain = snapshot()
            assertFalse(parchment.sameAs(night)); assertFalse(parchment.sameAs(plain))
            assertEquals(ReaderPaper.NIGHT.background, night.getPixel(0, 0))
            assertEquals(ReaderPaper.PARCHMENT.background, plain.getPixel(0, 0))
            assertSame(before, view.document)
            assertEquals(offset, view.readingOffset)
            assertEquals(page, view.pageIndex)
            assertEquals(0, positions)
            listOf(parchment, night, plain).forEach { it.recycle() }
        } finally { view.dispose() }
    }
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

    private fun event(view: NativeTextView, action: Int, x: Float, y: Float, time: Long, elapsed: Long) {
        MotionEvent.obtain(time, time + elapsed, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
    }
    private fun waitLayout(view: NativeTextView, condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(condition())
    }
    @Test fun chapterBoundaryWaitsForReleaseAndLateTouchEventsCannotTurnAgain() {
        val view = reader()
        try {
            view.seek(view.document!!.pages.last().start)
            val anchor = view.readingOffset
            var boundaries = 0; var menus = 0
            view.onBoundary = { boundaries++ }; view.onMenu = { menus++ }
            val time = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 380f, 450f, time, 0)
            event(view, MotionEvent.ACTION_MOVE, 90f, 450f, time, 60)
            assertEquals(0, boundaries); assertEquals(anchor, view.readingOffset)
            event(view, MotionEvent.ACTION_UP, 90f, 450f, time, 120)
            assertEquals(1, boundaries)
            view.boundaryResolved("已到本书末尾")
            event(view, MotionEvent.ACTION_UP, 216f, 450f, time, 140)
            assertEquals(1, boundaries); assertEquals(0, menus); assertEquals(anchor, view.readingOffset)
        } finally { view.dispose() }
    }
    @Test fun cancelledBoundaryDragNeverOpensAnotherChapter() {
        val view = reader()
        try {
            view.seek(view.document!!.pages.last().start)
            var boundaries = 0; view.onBoundary = { boundaries++ }
            gesture(view, 380f, 90f, cancel = true)
            assertEquals(0, boundaries)
        } finally { view.dispose() }
    }
    @Test fun scrollingFlingStopsAtChapterEndWithoutAnAutomaticChapterJump() {
        val view = reader()
        try {
            view.configure(ReaderSettings(pageMode = ReaderPageMode.SCROLL))
            view.seek(view.document!!.pages[view.document!!.pages.lastIndex - 1].start)
            var boundaries = 0; view.onBoundary = { boundaries++ }
            val time = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 216f, 650f, time, 0)
            event(view, MotionEvent.ACTION_MOVE, 216f, 450f, time, 20)
            event(view, MotionEvent.ACTION_UP, 216f, 450f, time, 40)
            repeat(120) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)); view.computeScroll() }
            assertEquals(0, boundaries)
            assertEquals(view.document!!.pages.lastIndex, view.pageIndex)
        } finally { view.dispose() }
    }
    @Test fun scrollingPastTheEndRequiresARealPullAndRelease() {
        val view = reader()
        try {
            view.configure(ReaderSettings(pageMode = ReaderPageMode.SCROLL))
            view.seek(view.document!!.pages.last().start)
            var boundaries = 0; view.onBoundary = { boundaries++ }
            val time = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 216f, 650f, time, 0)
            event(view, MotionEvent.ACTION_MOVE, 216f, 250f, time, 60)
            assertEquals(0, boundaries)
            event(view, MotionEvent.ACTION_UP, 216f, 250f, time, 120)
            assertEquals(1, boundaries)
        } finally { view.dispose() }
    }
    @Test fun brightnessDragPreviewsImmediatelyButCommitsOnlyOnReleaseAndCancelRestores() {
        val view = reader()
        try {
            var commits = 0; var previews = 0; var restored = false
            view.onSettings = { commits++ }
            view.onBrightnessPreview = { if (it == null) restored = true else previews++ }
            val time = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 12f, 450f, time, 0)
            event(view, MotionEvent.ACTION_MOVE, 12f, 650f, time, 60)
            assertTrue(previews > 0); assertEquals(0, commits)
            event(view, MotionEvent.ACTION_CANCEL, 12f, 650f, time, 120)
            assertTrue(restored); assertEquals(0, commits)
            event(view, MotionEvent.ACTION_DOWN, 12f, 450f, time + 200, 0)
            event(view, MotionEvent.ACTION_MOVE, 12f, 250f, time + 200, 60)
            event(view, MotionEvent.ACTION_UP, 12f, 250f, time + 200, 120)
            assertEquals(1, commits)
        } finally { view.dispose() }
    }
    @Test fun repaginationDropsTheOldGestureAndPreservesTheCharacterAnchor() {
        val view = reader()
        try {
            val anchor = view.document!!.pages[2].start + 4
            view.seek(anchor)
            var menus = 0; view.onMenu = { menus++ }
            val old = view.document
            val time = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 216f, 450f, time, 0)
            view.configure(ReaderSettings(fontSize = 150f, verticalMargins = 1.8f, pageMode = ReaderPageMode.NONE))
            waitLayout(view) { view.document !== old }
            event(view, MotionEvent.ACTION_UP, 216f, 450f, time, 120)
            assertEquals(0, menus); assertEquals(anchor, view.readingOffset)
        } finally { view.dispose() }
    }

    @Test fun aSecondTapCannotCancelAnAlreadyCommittedPageAnimation() {
        val view = reader()
        try {
            view.configure(ReaderSettings(pageMode = ReaderPageMode.SLIDE))
            var menus = 0; view.onMenu = { menus++ }
            view.turn(1)
            gesture(view, 216f, 216f)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertEquals(1, view.pageIndex); assertEquals(0, menus)
        } finally { view.dispose() }
    }
    @Test fun longPressOnThePageHeaderCannotSelectAnUnrelatedParagraph() {
        val view = reader()
        try {
            var excerpts = 0; view.onExcerpt = { _, _ -> excerpts++ }
            val time = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 216f, 15f, time, 0)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
            event(view, MotionEvent.ACTION_UP, 216f, 15f, time, 750)
            assertEquals(0, excerpts)
            val line = view.document!!.pages.first().lines.first()
            val next = SystemClock.uptimeMillis()
            event(view, MotionEvent.ACTION_DOWN, 216f, line.baseline - 4, next, 0)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
            event(view, MotionEvent.ACTION_UP, 216f, line.baseline - 4, next, 750)
            assertEquals(1, excerpts)
        } finally { view.dispose() }
    }
}
