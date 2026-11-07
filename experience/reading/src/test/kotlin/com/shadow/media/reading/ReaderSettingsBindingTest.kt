package com.shadow.media.reading

import com.shadow.media.database.NovelPublicationEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderSettingsBindingTest {
    @Test fun sourceAndChapterChangesRetainEveryCurrentTypographySetting() {
        val context = RuntimeEnvironment.getApplication()
        val defaults = ReaderSettingsStore(context.getSharedPreferences("binding-defaults", 0))
        defaults.save(ReaderSettings(fontSize = 130f, font = ReaderFont.SANS, bold = true, lineHeight = 1.9f,
            paragraphSpacing = .9f, letterSpacing = .02f, margins = 1.4f, paper = ReaderPaper.NIGHT))
        val binding = ReaderSettingsBinding { id -> ReaderSettingsStore(context.getSharedPreferences("binding-$id", 0), defaults) }
        val pub = NovelPublicationEntity("asset-one", "novel:test", "work", "v1", "{}", "r1")
        val key = readerBookSettingsKey("asset-one", pub)
        val (_, current) = binding.bind(key, ReaderSettings())
        // A change elsewhere must not silently reflow a source switch in this reader.
        defaults.save(ReaderSettings(fontSize = 180f, lineHeight = 1.2f, margins = .7f, paper = ReaderPaper.WHITE))
        val alternate = pub.copy(assetId = "asset-two", versionId = "v2", packageRevision = "r2")
        assertEquals(key, readerBookSettingsKey("asset-two", alternate))
        val (_, switched) = binding.bind(readerBookSettingsKey("asset-two", alternate), current)
        assertEquals(current, switched)
        assertEquals(ReaderFont.SANS, switched.font)
        val (_, otherBook) = binding.bind("another-book", switched)
        assertEquals(180f, otherBook.fontSize)
    }
}
