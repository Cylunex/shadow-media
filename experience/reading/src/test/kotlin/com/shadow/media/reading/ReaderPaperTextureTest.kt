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
    @Test fun nativePageDoesNotPaintOutsideItsInsetViewport() {
        val bitmap = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.MAGENTA)
        val view = NativeTextView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 432, 880)
        canvas.translate(0f, 48f)
        view.draw(canvas)
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(100, 20))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(100, 940))
        assertEquals(ReaderSettings().paper.background, bitmap.getPixel(100, 100))
        bitmap.recycle()
    }
    @Test fun systemBarsAndBodyShareOneContinuousPaperTexture() {
        val texture = ReaderPaperTexture.bitmap(RuntimeEnvironment.getApplication())
        val full = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888)
        val body = Bitmap.createBitmap(432, 880, Bitmap.Config.ARGB_8888)
        for (paper in listOf(ReaderPaper.PARCHMENT, ReaderPaper.DEEP_PARCHMENT, ReaderPaper.NIGHT)) {
            val settings = ReaderSettings(paper = paper)
            drawReaderPaper(Canvas(full), 432f, 960f, settings, texture)
            drawReaderPaper(Canvas(body), 432f, 880f, settings, texture, topInset = 48f, bottomInset = 32f)
            for (y in 0 until 880 step 11) for (x in 0 until 432 step 11) {
                assertEquals("Paper must not restart at the status bar", full.getPixel(x, y + 48), body.getPixel(x, y))
            }
        }
        full.recycle(); body.recycle()
    }
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
    @Test fun deepParchmentHasDarkerWarmGrainWithReadableText() {
        val texture = ReaderPaperTexture.bitmap(RuntimeEnvironment.getApplication())
        val light = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888)
        val deep = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888)
        drawReaderPaper(Canvas(light), 432f, 900f, ReaderSettings(), texture)
        drawReaderPaper(Canvas(deep), 432f, 900f, ReaderSettings(paper = ReaderPaper.DEEP_PARCHMENT), texture)
        assertFalse(light.sameAs(deep))
        for (strength in listOf(.65f, 1f)) {
            drawReaderPaper(Canvas(deep), 432f, 900f, ReaderSettings(paper = ReaderPaper.DEEP_PARCHMENT, paperTexture = strength), texture)
            for (y in 0 until 900 step 8) for (x in 0 until 432 step 8) {
                val color = deep.getPixel(x, y)
                assertTrue(ColorUtils.calculateLuminance(color) < ColorUtils.calculateLuminance(light.getPixel(x, y)))
                assertTrue("Deep parchment must keep readable contrast", ColorUtils.calculateContrast(ReaderPaper.DEEP_PARCHMENT.foreground, color) >= 4.5)
            }
        }
        val script = readerPaperScript(ReaderSettings(paper = ReaderPaper.DEEP_PARCHMENT), ReaderPaperTexture.dataUri(RuntimeEnvironment.getApplication()))
        assertTrue(script.contains("normal,multiply"))
        assertTrue((org.json.JSONTokener(script.substringAfter("style.textContent = ")).nextValue() as String).contains("data:image/"))
        light.recycle(); deep.recycle()
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
