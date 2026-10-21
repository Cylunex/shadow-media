package com.shadow.media.reading

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.StaticLayout
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.shadow.media.reading.legado.LineColumnLayout
import com.shadow.media.reading.legado.ZhLayout
import java.util.Locale

internal data class NativeGlyph(val text: String, val x: Float)
internal data class NativeTextLine(val start: Int, val end: Int, val baseline: Float, val glyphs: List<NativeGlyph>)
internal data class NativeTextPage(val start: Int, val end: Int, val lines: List<NativeTextLine>, val title: Boolean)

/** A page is a projection of immutable text offsets. Reflow never changes the stored reading anchor. */
internal class NativeTextLayout(
    val chapter: NativeTextChapter,
    val width: Int,
    val height: Int,
    val settings: ReaderSettings,
    val density: Float,
    val scaledDensity: Float,
    val pages: List<NativeTextPage>,
) {
    private val margin = 22f * density * settings.margins
    private val bodyPaint = textPaint(settings, scaledDensity)
    private val furniture = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * scaledDensity
        color = settings.paper.foreground
        alpha = 145
    }
    private val titlePaint = headingPaint(bodyPaint, height, density)
    fun pageAt(offset: Int): Int {
        var low = 0; var high = pages.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (pages[mid].start <= offset) low = mid else high = mid - 1
        }
        return low
    }
    fun offsetAt(page: Int, vertical: Float): Int = pages[page].lines.firstOrNull { it.baseline >= vertical + 40f * density }?.start ?: pages[page].end
    fun draw(canvas: Canvas, page: Int, bookTitle: String) {
        canvas.drawColor(settings.paper.background)
        val contentWidth = (width - 2 * margin).toInt().coerceAtLeast(1)
        val header = TextUtils.ellipsize(if (pages[page].title) bookTitle else chapter.ref.title, furniture, contentWidth.toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(header.toString(), margin, 24f * density, furniture)
        if (pages[page].title) {
            val title = titleLayout(chapter.ref.title, titlePaint, contentWidth)
            canvas.save(); canvas.translate(margin, 52f * density); title.draw(canvas); canvas.restore()
        }
        for (line in pages[page].lines) for (glyph in line.glyphs) canvas.drawText(glyph.text, margin + glyph.x, line.baseline, bodyPaint)
        val footer = "${chapter.ref.ordinal + 1}/${chapter.total} 章"
        canvas.drawText(footer, margin, height - 18f * density, furniture)
        val number = "${page + 1} / ${pages.size}"
        canvas.drawText(number, width - margin - furniture.measureText(number), height - 18f * density, furniture)
    }

    companion object {
        private fun textPaint(settings: ReaderSettings, scaledDensity: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = 20f * scaledDensity * settings.fontSize / 100f
            color = settings.paper.foreground
            typeface = Typeface.create(settings.font.css, if (settings.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        private fun headingPaint(body: TextPaint, height: Int, density: Float) = TextPaint(body).apply {
            textSize = minOf(body.textSize * 1.2f, (height - 100f * density).coerceAtLeast(40f * density) * .4f)
            typeface = Typeface.create(body.typeface, Typeface.BOLD)
        }
        private fun titleLayout(title: String, paint: TextPaint, width: Int) = StaticLayout.Builder.obtain(title, 0, title.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()

        suspend fun paginate(chapter: NativeTextChapter, width: Int, height: Int, settings: ReaderSettings, density: Float, scaledDensity: Float): NativeTextLayout {
            require(width > 0 && height > 0)
            val paint = textPaint(settings, scaledDensity)
            val margin = 22f * density * settings.margins
            val contentWidth = (width - 2 * margin).toInt().coerceAtLeast((paint.textSize * 5).toInt())
            val titlePaint = headingPaint(paint, height, density)
            val top = 48f * density
            val bottom = (height - 48f * density).coerceAtLeast(top + paint.textSize * 2)
            val lineHeight = paint.textSize * settings.lineHeight
            val fm = paint.fontMetrics
            val pages = mutableListOf<NativeTextPage>()
            var lines = mutableListOf<NativeTextLine>()
            var y = 52f * density + titleLayout(chapter.ref.title, titlePaint, contentWidth).height + paint.textSize * 0.85f
            var firstPage = true
            var firstParagraph = true
            val breaker = BreakIterator.getCharacterInstance(Locale.CHINA)
            var paragraphStart = 0
            fun emitLine(start: Int, end: Int, glyphs: List<NativeGlyph>) {
                if (y - fm.ascent + fm.descent > bottom && (lines.isNotEmpty() || firstPage)) {
                    pages += NativeTextPage(if (pages.isEmpty()) 0 else lines.first().start, start, lines, firstPage)
                    firstPage = false; lines = mutableListOf(); y = top
                }
                lines += NativeTextLine(start, end, y - fm.ascent, glyphs)
                y += lineHeight
            }
            for (paragraph in chapter.text.splitToSequence('\n')) {
                currentCoroutineContext().ensureActive()
                val leading = paragraph.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) paragraph.length else it }
                val end = paragraph.indexOfLast { !it.isWhitespace() } + 1
                if (end > leading) {
                    val duplicateHeading = firstParagraph && paragraph.substring(leading, end) == chapter.ref.title
                    firstParagraph = false
                    if (!duplicateHeading) {
                        var chunkStart = leading
                        while (chunkStart < end) {
                            currentCoroutineContext().ensureActive()
                            var chunkEnd = safeTextOffset(paragraph, (chunkStart + 4096).coerceAtMost(end))
                            if (chunkEnd < end) {
                                val punctuation = paragraph.lastIndexOfAny(charArrayOf('。', '！', '？', '；'), chunkEnd - 1)
                                if (punctuation >= chunkEnd - 200 && punctuation >= chunkStart) chunkEnd = punctuation + 1
                                val boundary = BreakIterator.getCharacterInstance(Locale.CHINA).apply { setText(paragraph) }
                                if (!boundary.isBoundary(chunkEnd)) chunkEnd = boundary.preceding(chunkEnd)
                            }
                            val indent = if (chunkStart == leading && settings.indent) "　　" else ""
                            val display = indent + paragraph.substring(chunkStart, chunkEnd)
                            breaker.setText(display)
                            val words = mutableListOf<String>()
                            val starts = mutableListOf<Int>()
                            var a = breaker.first(); var b = breaker.next()
                            while (b != BreakIterator.DONE) { starts += a; words += display.substring(a, b); a = b; b = breaker.next() }
                            starts += display.length
                            val widths = words.map { paint.measureText(it) + paint.textSize * settings.letterSpacing }
                            val layout = ZhLayout(display, paint, contentWidth, words, widths, indent.length)
                            for (line in 0 until layout.lineCount) {
                                val from = starts.binarySearch(layout.getLineStart(line)).coerceAtLeast(0)
                                val until = starts.binarySearch(layout.getLineStart(line + 1)).let { if (it >= 0) it else words.size }
                                if (until <= from) continue
                                val lineWords = words.subList(from, until)
                                val lineWidths = widths.subList(from, until)
                                val glyphs = mutableListOf<NativeGlyph>()
                                val emit: (Int, Float, Float, Int) -> Unit = { index, x, _, _ -> glyphs += NativeGlyph(lineWords[index], x) }
                                val desired = lineWidths.sum()
                                // The last line of each paragraph stays naturally spaced, as in Legado.
                                if (line < layout.lineCount - 1 || chunkEnd < end) {
                                    if (line == 0 && indent.isNotEmpty() && lineWords.size > indent.length) {
                                        LineColumnLayout.justifiedFirst(lineWords, lineWidths, contentWidth.toFloat(), desired,
                                            indent.length, paint.measureText("　") + paint.textSize * settings.letterSpacing,
                                            0f, onIndentWidth = {}, onJustify = { _, _, _ -> }, emit = emit)
                                    } else LineColumnLayout.justified(lineWords, lineWidths, contentWidth.toFloat(), desired, 0f, { _, _, _ -> }, emit)
                                } else LineColumnLayout.natural(lineWidths, 0f, false, 0, 0f, onIndentWidth = {}, emit = emit)
                                val rawStart = paragraphStart + chunkStart + (layout.getLineStart(line) - indent.length).coerceAtLeast(0)
                                val rawEnd = paragraphStart + chunkStart + (layout.getLineStart(line + 1) - indent.length).coerceAtLeast(0)
                                emitLine(rawStart, rawEnd, glyphs)
                            }
                            chunkStart = chunkEnd
                        }
                        y += paint.textSize * settings.paragraphSpacing
                    }
                }
                paragraphStart += paragraph.length + 1
            }
            pages += NativeTextPage(if (pages.isEmpty()) 0 else lines.firstOrNull()?.start ?: chapter.text.length, chapter.text.length, lines, firstPage)
            return NativeTextLayout(chapter, width, height, settings, density, scaledDensity, pages)
        }
    }
}
