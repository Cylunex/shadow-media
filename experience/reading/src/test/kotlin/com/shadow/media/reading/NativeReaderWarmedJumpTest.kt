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
class NativeReaderWarmedJumpTest {
    @Test fun warmedChapterJumpSavesBeforeAnyFurtherPageTurn() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "native-warm-jump.txt").apply {
            writeText("第一章 山海来信\n" + "少年提灯前行。\n".repeat(30) + "第二章 山外\n" + "远处的灯光亮了。\n".repeat(30))
        }
        val library = LibraryRepository(context)
        val id = LibraryRepository.digest("native-warm-jump")
        val item = library.importPrepared(id, "local", id, "山海", "txt", file)
        val controller = Robolectric.buildActivity(NativeTextReaderActivity::class.java, Intent(context, NativeTextReaderActivity::class.java).putExtra("assetId", item.id)).setup().visible()
        try {
            val activity = controller.get()
            val field = NativeTextReaderActivity::class.java.getDeclaredField("view").apply { isAccessible = true }
            fun pump() { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)); Thread.sleep(10) }
            var view: NativeTextView? = null
            repeat(300) { if (view?.document == null) { pump(); view = field.get(activity) as? NativeTextView } }
            val reader = requireNotNull(view)
            assertNotNull(reader.document)
            val source = NativeTextSource(library, com.shadow.media.library.NovelRepository(context, library))
            val next = source.local(item)[1]
            reader.prepareChapter(next)
            NativeTextReaderActivity::class.java.getDeclaredMethod("skipChapter", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1)
            repeat(300) { if (reader.document?.chapter?.ref?.id != next.ref.id) pump() }
            assertEquals(next.ref.id, reader.document?.chapter?.ref?.id)
            com.shadow.media.library.ProgressWriter.flush()
            val saved = requireNotNull(library.dao.progress(item.id))
            assertEquals(next.ref.href, org.json.JSONObject(saved.locatorJson).getString("href"))
        } finally { controller.pause().stop().destroy(); file.delete() }
    }

}
