package com.shadow.media.reading

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.shadow.media.library.LibraryRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeReaderActivityInteractionTest {
    @Test fun openingAndClosingTheMenuPreservesViewportAnchorAndBackClosesItFirst() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "native-interaction.txt").apply { writeText("第一章 山海来信\n" + "山间的雾气渐渐散去，少年提灯前行。\n".repeat(80)) }
        val id = LibraryRepository.digest("native-interaction-book")
        val item = LibraryRepository(context).importPrepared(id, "local", id, "山海来信", "txt", file)
        val controller = Robolectric.buildActivity(NativeTextReaderActivity::class.java, Intent(context, NativeTextReaderActivity::class.java).putExtra("assetId", item.id)).setup().visible()
        try {
            val activity = controller.get()
            fun find(view: View): NativeTextView? {
                if (view is NativeTextView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            var reader: NativeTextView? = null
            val until = System.nanoTime() + 10_000_000_000
            while (reader?.document == null && System.nanoTime() < until) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)); Thread.sleep(10)
                reader = find(activity.window.decorView)
            }
            val view = requireNotNull(reader)
            assertNotNull(view.document)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            val document = view.document
            val anchor = view.readingOffset
            val fullScreen = activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN
            val now = SystemClock.uptimeMillis()
            for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0, MotionEvent.ACTION_UP to 60)) {
                MotionEvent.obtain(now, now + delay, action, view.width / 2f, view.height / 2f, 0).also { view.onTouchEvent(it); it.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            assertEquals(fullScreen, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
            assertSame(document, view.document); assertEquals(anchor, view.readingOffset)
            System.getenv("NATIVE_READER_SCREENSHOTS")?.let { folder ->
                val decor = activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(bitmap))
                File(folder).mkdirs()
                File(folder, "native-reader-client-menu.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            activity.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            assertFalse(activity.isFinishing)
            assertSame(document, view.document); assertEquals(anchor, view.readingOffset)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy(); file.delete() }
    }
}
