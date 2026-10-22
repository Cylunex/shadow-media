package com.shadow.media.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.shadow.media.reading.legado.LegadoPageCurl
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeTextLayoutTest {
    private data class Preview(val name: String, val style: ReaderSettings, val width: Int, val height: Int)
    @Test fun wideScreensHaveCenteredMeasureAndFocusModeReclaimsFurnitureSpace() = runBlocking {
        val content = chapter("山间的雾气渐渐散去，石阶旁的竹叶还挂着露珠。\n".repeat(140))
        val wide = layout(content, w = 1200, h = 800)
        assertEquals(640, wide.contentWidth)
        assertEquals(280f, wide.margin)
        val full = layout(content, ReaderSettings(comfortableWidth = false), 1200, 800)
        assertTrue(full.contentWidth > wide.contentWidth)
        val focus = layout(content, ReaderSettings(showProgress = false))
        val normal = layout(content)
        assertTrue(focus.pages.size <= normal.pages.size)
        assertTrue(focus.pages.first().lines.first().baseline < normal.pages.first().lines.first().baseline)
        assertTrue(focus.pages[1].lines.first().baseline < normal.pages[1].lines.first().baseline)
        val offset = normal.pages[3].start
        assertTrue(offset in focus.pages[focus.pageAt(offset)].let { it.start..it.end })
    }
    @Test fun largeFontAndWideMarginsCannotMakeTheTextColumnWiderThanTheViewport() = runBlocking {
        val content = chapter("长路漫漫山海之间，雨声穿过竹林。".repeat(30))
        val result = layout(content, ReaderSettings(fontSize = 220f, margins = 2f, justify = false), 240, 700)
        assertTrue(result.contentWidth <= 224)
        assertTrue(result.margin >= 8f)
        assertEquals(content.text, result.pages.flatMap { it.lines }.flatMap { it.glyphs }.joinToString("") { it.text }.replace("　", ""))
        val paint = android.text.TextPaint().apply { textSize = 44f; typeface = android.graphics.Typeface.SERIF }
        for (line in result.pages.flatMap { it.lines }) for (glyph in line.glyphs) {
            assertTrue(result.margin + glyph.x + paint.measureText(glyph.text) <= 240f)
        }
    }
    private fun chapter(body: String) = NativeTextChapter(NativeChapterRef("c0", "第一章 风起青萍", 0, "c0.xhtml"), body, "version1", 3)
    private suspend fun layout(content: NativeTextChapter, settings: ReaderSettings = ReaderSettings(), w: Int = 432, h: Int = 900) = NativeTextLayout.paginate(content, w, h, settings, 1f, 1f)
    @Test fun chinesePunctuationAndSupplementaryCharactersHaveCompleteNonOverlappingLines() = runBlocking {
        val text = ("“风起了。”他说：“去看看《山海经》吧！”𠀀😀春风又绿江南岸。\n".repeat(65))
        val content = chapter(text)
        val result = layout(content)
        assertTrue(result.pages.size > 2)
        assertEquals(0, result.pages.first().start)
        assertEquals(text.length, result.pages.last().end)
        result.pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        val lines = result.pages.flatMap { it.lines }
        val rendered = lines.flatMap { it.glyphs }.joinToString("") { it.text }.replace("　", "")
        assertEquals(text.replace("\n", ""), rendered)
        for (line in lines) {
            assertFalse(line.glyphs.first().text in listOf("，", "。", "！", "？", "”", "》", "）"))
            assertFalse(line.glyphs.last().text in listOf("“", "《", "（"))
            assertTrue(line.glyphs.all { it.x.isFinite() && it.x >= -0.1f && it.x < result.width })
            assertFalse(line.start in 1 until text.length && text[line.start].isLowSurrogate())
        }
    }
    @Test fun characterAnchorSurvivesReflowAndRejectsChangedTextOrVersion() = runBlocking {
        val content = chapter("一段中文小说正文，测试分页、断行与位置恢复。\n".repeat(140))
        val first = layout(content)
        val savedOffset = first.pages[3].start
        val locator = content.locator(savedOffset)
        for ((w, font) in listOf(360 to 150f, 760 to 100f, 432 to 220f)) {
            val other = layout(content, ReaderSettings(fontSize = font), w)
            assertEquals(savedOffset, content.restore(locator))
            val page = other.pages[other.pageAt(savedOffset)]
            assertTrue(savedOffset in page.start..page.end)
        }
        val changed = content.copy(text = content.text.repeat(2))
        assertNotEquals(savedOffset, changed.restore(locator))
        assertEquals(0, content.restore(JSONObject().put("locations", JSONObject().put("progression", Double.NaN.toString()))))
    }
    @Test fun txtHeadingsEncodingAndLegacyHrefRestoreAreSupported() {
        val text = "第一章 山雨\n　　这是第一章。\n\n第二章 风声\n这是第二章。𠀀\n"
        for (encoding in listOf("UTF-8", "GB18030", "UTF-16LE")) {
            val decoded = com.shadow.media.library.TextPublication.decode(text.toByteArray(charset(encoding)), encoding)
            val chapters = NativeTextSource.parseTxt(decoded, "v1")
            assertEquals(2, chapters.size)
            assertEquals("c1.xhtml", chapters[1].ref.href)
            assertTrue(chapters[1].text.contains("第二章"))
            val legacy = JSONObject().put("href", "c1.xhtml").put("locations", JSONObject().put("progression", .5))
            assertEquals(chapters[1].text.length / 2, chapters[1].restore(legacy))
        }
    }
    @Test fun largeFontInLandscapeKeepsBodyAboveFooterAndCanPlaceHeadingOnItsOwnPage() = runBlocking {
        val content = chapter("第一段中文小说正文，验证大字体横屏下的分页。\n".repeat(60))
        val result = layout(content, ReaderSettings(fontSize = 220f), 720, 220)
        val font = android.text.TextPaint().apply { textSize = 44f }.fontMetrics
        assertTrue(result.pages.size > 1)
        for (page in result.pages) for (line in page.lines) {
            assertTrue(line.baseline + font.descent <= 220 - 48f + 0.1f)
        }
    }
    @Test fun unbrokenTxtIsBoundedWithoutDroppingTextAndRetainsLegacySectionCoordinates() {
        val text = "长段正文𠀀，".repeat(30_000)
        val chapters = NativeTextSource.parseTxt(text, "v1")
        assertTrue(chapters.size > 2)
        assertTrue(chapters.all { it.text.length <= 48_000 })
        assertEquals(text + "\n", chapters.joinToString("") { it.text })
        val oldOffset = ((text.length + 1) * .8).toInt()
        val target = chapters.last { it.legacyStart <= oldOffset }
        val legacy = JSONObject().put("href", "c0.xhtml").put("locations", JSONObject().put("progression", .8))
        assertEquals(safeTextOffset(target.text, oldOffset - target.legacyStart), target.restore(legacy))
        val withBlanks = NativeTextSource.parseTxt("\n\n第一章 开始\n正文", "v1")
        assertEquals(1, withBlanks.size)
        assertEquals("c1.xhtml", withBlanks.first().ref.href)
    }
    @Test fun hugeSingleParagraphAndBlankLinesDoNotDropText() = runBlocking {
        val text = "\n\n" + "长段落𠀀的测试，".repeat(1800) + "\n\n"
        val result = layout(chapter(text))
        assertEquals(text.trim(), result.pages.flatMap { it.lines }.flatMap { it.glyphs }.joinToString("") { it.text }.replace("　", ""))
        assertTrue(result.pages.all { it.lines.isNotEmpty() })
    }
    @Test fun nativePagesAndLegadoCurlRenderActualAdjacentContent(): Unit = runBlocking {
        val text = ("　　山间的雾气渐渐散去，石阶旁的竹叶还挂着露珠。少年收起书卷，望向远处的灯火。\n" +
            "“今日便出发吗？”她轻声问道。\n" + "　　他点了点头：“路虽远，总要一步一步走。”\n").repeat(24)
        val result = layout(chapter(text))
        val texture = ReaderPaperTexture.bitmap(org.robolectric.RuntimeEnvironment.getApplication())
        val first = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888)
        result.draw(Canvas(first), 0, "山海夜读", texture)
        result.draw(Canvas(second), 1, "山海夜读", texture)
        assertFalse(first.sameAs(second))
        val curl = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888)
        LegadoPageCurl().draw(Canvas(curl), first, second, .4f, true, ReaderSettings().paper.background)
        assertFalse(curl.sameAs(first)); assertFalse(curl.sameAs(second))
        // Optional visual inspection artifacts stay outside the source tree.
        System.getenv("NATIVE_READER_SCREENSHOTS")?.let { path ->
            File(path).mkdirs()
            for ((name, bitmap) in listOf("native-page" to first, "native-next" to second, "native-curl" to curl)) {
                File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            for ((name, style, w, h) in listOf(
                Preview("reader-parchment-focus", ReaderSettings(showProgress = false), 432, 900),
                Preview("reader-night", ReaderSettings(paper = ReaderPaper.NIGHT), 432, 900),
                Preview("reader-wide", ReaderSettings(), 1100, 620),
                Preview("reader-large-narrow", ReaderTypographyPreset.LARGE.apply(ReaderSettings()), 320, 700)
            )) {
                val page = layout(chapter(text), style, w, h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                page.draw(Canvas(bitmap), 0, "山海夜读", texture)
                File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
