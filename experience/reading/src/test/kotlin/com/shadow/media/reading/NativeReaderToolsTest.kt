package com.shadow.media.reading

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeReaderToolsTest {
    private fun chapter(index: Int, text: String) = NativeTextChapter(
        NativeChapterRef("c$index", "第 $index 章", index, "chapters/c$index.xhtml"), text, "v1", 5_000)
    private fun location(index: Int, offset: Int = 0) = NativeReaderLocation("window-$index", chapter(index, "正文".repeat(100)).locator(offset).toString())

    @Test fun searchStopsLoadingChaptersAtTheResultLimitAndKeepsExactUnicodeAnchors() = runBlocking {
        val text = "𠀀😀开始" + "找到了𠀀".repeat(300)
        var loads = 0
        val content = chapter(0, text)
        val entries = listOf(NativeSearchEntry("a", content.ref) { loads++; content },
            NativeSearchEntry("b", chapter(1, "正文").ref) { loads++; error("Result limit must avoid this IO") })
        val result = searchNativeText(entries, " 找到了 ").last()
        assertTrue(result.complete)
        assertTrue(result.limited)
        assertEquals(200, result.hits.size)
        assertEquals(1, loads)
        assertEquals(1, result.scanned)
        result.hits.forEachIndexed { i, hit ->
            val offset = text.indexOf("找到了") + i * "找到了𠀀".length
            assertEquals(offset, content.restore(JSONObject(hit.locator)))
            assertTrue(hit.preview.length <= 120)
            assertFalse(hit.preview.first().isLowSurrogate())
            assertFalse(hit.preview.last().isHighSurrogate())
            assertTrue(hit.locator.length < 1000)
        }
    }

    @Test fun unreadableChapterDoesNotDiscardEarlierMatchesAndSearchIsCaseInsensitive() = runBlocking {
        val entries = (0..2).map { i -> NativeSearchEntry("a$i", chapter(i, "").ref) {
            if (i == 1) error("Damaged cached package")
            chapter(i, "Before NEEdle after needle")
        } }
        val batches = searchNativeText(entries, "needle").toList()
        assertFalse(batches.first().complete)
        assertEquals(2, batches.first().hits.size)
        val result = batches.last()
        assertEquals(4, result.hits.size)
        assertEquals(1, result.skipped)
        assertEquals(3, result.scanned)
        assertFalse(result.limited)
    }

    @Test fun cancellationInterruptsAnInFlightChapterLoadAndNeverLoadsTheRest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var laterLoaded = false
        val task = launch {
            searchNativeText(listOf(
                NativeSearchEntry("a", chapter(0, "").ref) { started.complete(Unit); awaitCancellation() },
                NativeSearchEntry("b", chapter(1, "").ref) { laterLoaded = true; chapter(1, "needle") },
            ), "needle").collect()
        }
        started.await()
        task.cancelAndJoin()
        assertFalse(laterLoaded)
    }

    @Test fun blankSearchDoesNotReadAnyChapterAndNoMatchReportsCompletion() = runBlocking {
        var loaded = false
        val entry = NativeSearchEntry("a", chapter(0, "").ref) { loaded = true; chapter(0, "正文") }
        assertTrue(searchNativeText(listOf(entry), "  ").last().hits.isEmpty())
        assertFalse(loaded)
        val result = searchNativeText(listOf(entry), "不存在").last()
        assertTrue(result.complete)
        assertEquals(1, result.scanned)
        assertTrue(result.hits.isEmpty())
    }

    @Test fun jumpHistorySurvivesRecreationAndKeepsTheOriginalImmutableWindowAndOffset() {
        val original = location(20, 51)
        val history = NativeJumpHistory().remember(original, location(4999))
        val restored = NativeJumpHistory.restore(history.toJson())
        assertEquals(original, restored.locations.last())
        assertEquals(51, JSONObject(restored.locations.last().locator).getJSONObject("native_text").getInt("offset"))
        assertEquals(restored, restored.remember(location(1), location(1)))
        assertEquals(restored, restored.remember(original, location(30)))
        var bounded = NativeJumpHistory()
        repeat(50) { bounded = bounded.remember(location(it), location(it + 1)) }
        assertEquals(20, NativeJumpHistory.restore(bounded.toJson()).locations.size)
        assertEquals(location(30), bounded.locations.first())
        assertTrue(NativeJumpHistory.restore("invalid").locations.isEmpty())
    }

    @Test fun longNovelDirectoryOpensThePageContainingCurrentOrRequestedChapter() {
        assertEquals(4_900, nativeContentsOffset(4_999, 5_000))
        assertEquals(100, nativeContentsOffset(100, 5_000))
        assertEquals(0, nativeContentsOffset(99, 5_000))
        assertEquals(200, nativeContentsOffset(Int.MAX_VALUE, 250))
        assertEquals(0, nativeContentsOffset(-1, 0))
    }
}
