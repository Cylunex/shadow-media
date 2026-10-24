package com.shadow.media.reading

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderFontsTest {
    @Test fun selectedFontIsCopiedPrivatelyAndSurvivesSettingsAndSourceFileRemoval() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = listOf(File("fixtures/reading/fonts/NotoSans-Regular.ttf"), File("../../fixtures/reading/fonts/NotoSans-Regular.ttf")).first { it.isFile }
        val selected = File(context.cacheDir, "chosen-font.ttf").apply { writeBytes(source.readBytes()) }
        val fonts = ReaderFonts(context)
        val (id, name) = fonts.import(Uri.fromFile(selected))
        assertTrue(id.matches(Regex("[a-f0-9]{64}")))
        assertTrue(selected.delete())
        val custom = ReaderSettings(customFontId = id, customFontName = name, verticalMargins = 1.8f, indentSize = 3,
            titleScale = 1.5f, titleSpacing = 1.4f, titleAlignment = ReaderTitleAlignment.CENTER, haptics = false)
        val preferences = context.getSharedPreferences("font-round-trip", 0)
        ReaderSettingsStore(preferences).save(custom)
        val restored = ReaderSettingsStore(preferences).load()
        assertEquals(custom, restored)
        assertNotNull(ReaderFonts(context).typeface(restored))
        assertTrue(File(context.filesDir, "reader-fonts/$id.font").isFile)
        assertNotNull(ReaderFonts(context).typeface(restored.copy(bold = true)))
    }
    @Test fun truncatedFontIsRejectedWithoutReplacingStoredTypography() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val font = File(context.cacheDir, "broken.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0) + ByteArray(16)) }
        try { ReaderFonts(context).import(Uri.fromFile(font)); fail("Invalid font directory") } catch (_: IllegalArgumentException) {}
        assertNull(ReaderFonts(context).typeface(ReaderSettings(customFontId = "../outside")))
    }
    @Test fun legacyPreferencesUseTheOriginalTypographyDefaults() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("old-reader-style", 0)
        preferences.edit().clear().putFloat("font", 130f).putBoolean("indent", false).commit()
        val restored = ReaderSettingsStore(preferences).load()
        assertEquals(130f, restored.fontSize); assertFalse(restored.indent)
        assertEquals(1f, restored.verticalMargins); assertEquals(2, restored.indentSize)
        assertTrue(restored.showChapterTitle); assertEquals(1.2f, restored.titleScale)
        assertEquals(ReaderTitleAlignment.START, restored.titleAlignment)
    }
}
