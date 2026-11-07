package com.shadow.media.reading

import com.shadow.media.library.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeTextRulesIntegrationTest {
    @Test fun removingAnAdAboveTheViewportRestoresTheUniqueSurvivingQuote() {
        val prefix = "广告语".repeat(100)
        val story = "旅人提灯经过石桥，远处的村庄升起炊烟。"
        val original = NativeTextChapter(NativeChapterRef("c", "第一章", 0, "c.xhtml"), prefix + story, "v", 1)
        val locator = original.locator(prefix.length)
        assertEquals(0, original.copy(text = story).restore(locator))
        assertNotEquals(0, original.copy(text = story, version = "other").restore(locator))
    }

    @Test fun cachedOriginalsCanBeCleanedAndRestoredOfflineWithoutNetwork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val library = LibraryRepository(context)
        val repository = NovelRepository(context, library)
        val file = File(context.cacheDir, "rules-test.txt").apply { writeText("第一章 山海\n广告语\n旅人提灯前行。\n") }
        val id = LibraryRepository.digest("text-rules-integration")
        val asset = library.importPrepared(id, "local", id, "山海", "txt", file)
        val source = NativeTextSource(library, repository)
        val raw = source.chapter(asset, null, null)
        val items = JSONArray().put(JSONObject().put("id", "ad").put("pattern", "广告语").put("replacement", "").put("enabled", true))
        repository.saveTextRules(null, items, 0)
        val previousOffline = LibraryResources.offlineOnly
        LibraryResources.offlineOnly = true
        try {
            val cleaned = source.chapter(asset, null, null)
            assertFalse(cleaned.text.contains("广告语"))
            assertTrue(cleaned.text.contains("旅人提灯前行。"))
            assertTrue(cleaned.originalText!!.contains("广告语"))
            assertNotEquals(raw.textHash, cleaned.textHash)
            repository.saveTextRules(null, JSONArray(), 1)
            assertEquals(raw.text, source.chapter(asset, null, null).text)
            assertTrue(library.localFile(asset).readText().contains("广告语"))
        } finally { LibraryResources.offlineOnly = previousOffline }
    }
}
