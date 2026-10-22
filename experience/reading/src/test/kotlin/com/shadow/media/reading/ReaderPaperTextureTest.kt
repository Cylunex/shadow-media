package com.shadow.media.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPaperTextureTest {
    @Test fun bundledPaperHasActualGrainAndReadableContrastEvenAtFullStrength() {
        val context = RuntimeEnvironment.getApplication()
        val texture = ReaderPaperTexture.bitmap(context)
        assertSame(texture, ReaderPaperTexture.bitmap(context))
        assertEquals(1024, texture.width)
        assertEquals(1536, texture.height)
        val colors = mutableSetOf<Int>()
        for (y in 0 until texture.height step 8) for (x in 0 until texture.width step 8) {
            val color = texture.getPixel(x, y)
            colors += color
            assertTrue("Full-strength grain must keep normal text at AA contrast", ColorUtils.calculateContrast(ReaderPaper.PARCHMENT.foreground, color) >= 4.5)
            val defaultPaper = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(color, 166), ReaderPaper.PARCHMENT.background)
            assertTrue("Default grain must keep normal text at AAA contrast", ColorUtils.calculateContrast(ReaderPaper.PARCHMENT.foreground, defaultPaper) >= 7)
        }
        assertTrue(colors.size > 100)
    }
    @Test fun textureCanBeMutedAndNeverLeaksIntoOtherPapers() {
        val texture = ReaderPaperTexture.bitmap(RuntimeEnvironment.getApplication())
        val bitmap = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888)
        for (paper in ReaderPaper.entries) {
            val value = ReaderSettings(paper = paper, paperTexture = 0f)
            drawReaderPaper(Canvas(bitmap), 432f, 900f, value, texture)
            assertEquals(paper.background, bitmap.getPixel(100, 100))
        }
        drawReaderPaper(Canvas(bitmap), 432f, 900f, ReaderSettings(paper = ReaderPaper.NIGHT), texture)
        assertEquals(ReaderPaper.NIGHT.background, bitmap.getPixel(100, 100))
        bitmap.recycle()
    }
    @Test fun epubUsesTheBundledImageAndRemovesItWhenThePaperChanges() {
        val data = ReaderPaperTexture.dataUri(RuntimeEnvironment.getApplication())
        assertTrue(data.startsWith("data:image/webp;base64,"))
        val script = org.json.JSONTokener(readerPaperScript(ReaderSettings(), data).substringAfter("style.textContent = ")).nextValue() as String
        assertTrue(script.contains("data:image/webp;base64,"))
        assertTrue(script.contains("background-size: cover"))
        assertFalse(script.contains("feTurbulence"))
        assertFalse(readerPaperScript(ReaderSettings(paper = ReaderPaper.NIGHT), data).contains("data:image/"))
        assertFalse(readerPaperScript(ReaderSettings(paperTexture = 0f), data).contains("data:image/"))
        assertEquals(readerPaperScript(ReaderSettings(), data), readerPaperScript(ReaderSettings(paperTexture = Float.NaN), data))
    }
}
