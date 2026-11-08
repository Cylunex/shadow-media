package com.shadow.media.reading

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.shadow.media.library.LibraryRepository
import com.shadow.media.library.LibraryResources
import com.shadow.media.library.NovelRepository
import com.shadow.media.database.NovelPublicationEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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
    @Test fun warmedChapterJumpSavesImmediatelyAndTurningPastTheLastPageCompletesTheBook() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "native-warm-jump.txt").apply {
            writeText("第一章 山海来信\n" + "少年提灯前行。\n".repeat(30) + "第二章 山外\n" + "远处的灯光亮了。\n".repeat(30))
        }
        val library = LibraryRepository(context)
        val id = LibraryRepository.digest("native-warm-jump")
        val item = library.importPrepared(id, "local", id, "山海", "txt", file)
        val controller = Robolectric.buildActivity(NativeTextReaderActivity::class.java, Intent(context, NativeTextReaderActivity::class.java).putExtra("assetId", item.id)).setup().visible()
        val releaseWriter = CompletableDeferred<Unit>()
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
            val nextContent = source.chapter(item, null, next.ref.href)
            reader.prepareChapter(nextContent)
            val warmed = NativeTextReaderActivity::class.java.getDeclaredField("warmedChapters").apply { isAccessible = true }
            repeat(300) { if (!(warmed.get(activity) as Map<*, *>).containsKey(1)) pump() }
            assertTrue("Adjacent content must already be prepared", (warmed.get(activity) as Map<*, *>).containsKey(1))
            // Simulate slow storage. Cached text turns must not await an unrelated disk barrier.
            val writerEntered = CompletableDeferred<Unit>()
            @Suppress("UNCHECKED_CAST")
            val writes = com.shadow.media.library.ProgressWriter::class.java.getDeclaredField("writes").apply { isAccessible = true }
                .get(null) as Channel<suspend () -> Unit>
            assertTrue(writes.trySend { writerEntered.complete(Unit); releaseWriter.await() }.isSuccess)
            writerEntered.await()
            NativeTextReaderActivity::class.java.getDeclaredMethod("skipChapter", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1)
            repeat(300) { if (reader.document?.chapter?.ref?.id != next.ref.id) pump() }
            assertEquals(next.ref.id, reader.document?.chapter?.ref?.id)
            assertFalse("The chapter is visible while storage is still busy", releaseWriter.isCompleted)
            releaseWriter.complete(Unit)
            com.shadow.media.library.ProgressWriter.flush()
            val saved = requireNotNull(library.dao.progress(item.id))
            assertEquals(next.ref.href, org.json.JSONObject(saved.locatorJson).getString("href"))
            assertFalse(saved.completed)

            reader.seek(reader.document!!.pages.last().start)
            reader.turn(1)
            repeat(300) { if (!reader.isEnabled) pump() }
            com.shadow.media.library.ProgressWriter.flush()
            val finished = requireNotNull(library.dao.progress(item.id))
            assertTrue(finished.completed)
            assertEquals(next.text.length, next.restore(org.json.JSONObject(finished.locatorJson)))
        } finally { releaseWriter.complete(Unit); controller.pause().stop().destroy(); file.delete() }
    }

    @Test fun offlineWindowTurnUsesPreparedTextAndKeepsTheLatestTypographyAndProgress() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val library = LibraryRepository(context)
        val novel = NovelRepository(context, library)
        val previousOffline = LibraryResources.offlineOnly
        LibraryResources.offlineOnly = true
        suspend fun window(ordinal: Int): com.shadow.media.database.LibraryAssetEntity {
            val row = JSONObject().put("id", "c$ordinal").put("title", "章节 $ordinal").put("ordinal", ordinal)
                .put("href", "chapters/c$ordinal.xhtml")
            val mapping = JSONObject().put("schema", "shadow.novel.publication/v1").put("work_id", "warm-book")
                .put("version_id", "v1").put("complete", false).put("version_complete", true).put("total", 2)
                .put("chapters", JSONArray().put(row))
            val file = File(context.cacheDir, "native-window-$ordinal.epub")
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                entry("META-INF/container.xml", """<container><rootfile full-path="book.opf"/></container>""")
                entry("book.opf", "<package><metadata><title>山海</title></metadata></package>")
                entry(row.getString("href"), "<html><body><p>山海来信第 $ordinal 章。</p></body></html>")
            }
            return library.importPrepared(LibraryRepository.digest("native-window-$ordinal"), "novel:warmed", "warm-book", "山海", "epub", file).also {
                novel.dao.putPublication(NovelPublicationEntity(it.id, it.providerId, "warm-book", "v1", mapping.toString(), it.revision))
            }
        }
        val first = window(0)
        val second = window(1)
        val controller = Robolectric.buildActivity(NativeTextReaderActivity::class.java,
            Intent(context, NativeTextReaderActivity::class.java).putExtra("assetId", first.id)).setup().visible()
        try {
            val activity = controller.get()
            fun pump() { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)); Thread.sleep(10) }
            val viewField = NativeTextReaderActivity::class.java.getDeclaredField("view").apply { isAccessible = true }
            val warmedField = NativeTextReaderActivity::class.java.getDeclaredField("warmedChapters").apply { isAccessible = true }
            repeat(300) { if ((viewField.get(activity) as? NativeTextView)?.document == null || !(warmedField.get(activity) as Map<*, *>).containsKey(1)) pump() }
            val reader = requireNotNull(viewField.get(activity) as? NativeTextView)
            assertTrue((warmedField.get(activity) as Map<*, *>).containsKey(1))
            val previousLayout = reader.document
            val largeType = reader.document!!.settings.copy(fontSize = 150f, lineHeight = 2f, font = ReaderFont.SANS)
            NativeTextReaderActivity::class.java.getDeclaredMethod("update", ReaderSettings::class.java).apply { isAccessible = true }.invoke(activity, largeType)
            repeat(300) { if (reader.document === previousLayout || !reader.isEnabled) pump() }
            // Warmed content survives transport eviction; chapter turns need no ZIP parsing.
            assertTrue(library.localFile(second).delete())
            NativeTextReaderActivity::class.java.getDeclaredMethod("skipChapter", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1)
            repeat(300) { if (reader.document?.chapter?.ref?.id != "c1" || !reader.isEnabled) pump() }
            assertEquals("c1", reader.document?.chapter?.ref?.id)
            assertTrue(reader.document!!.chapter.text.contains("第 1 章"))
            assertEquals(largeType.fontSize, reader.document!!.settings.fontSize)
            assertEquals(largeType.lineHeight, reader.document!!.settings.lineHeight)
            assertEquals(ReaderFont.SANS, reader.document!!.settings.font)
            com.shadow.media.library.ProgressWriter.flush()
            assertEquals("chapters/c1.xhtml", JSONObject(library.dao.progress(second.id)!!.locatorJson).getString("href"))
            val state = novel.dao.state(NovelRepository.stateId("novel:warmed", "warm-book"))!!
            assertEquals("c1", JSONObject(state.localPosition).getString("chapter_id"))
        } finally { controller.pause().stop().destroy(); LibraryResources.offlineOnly = previousOffline }
    }

}
