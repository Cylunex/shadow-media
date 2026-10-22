package com.shadow.media.reading

import kotlin.math.abs

internal enum class ReaderPageMode(val label: String) {
    CURL("仿真"), SLIDE("滑动"), COVER("覆盖"), NONE("无动画"), SCROLL("滚动")
}

internal enum class ReaderPaper(val label: String, val background: Int, val foreground: Int) {
    PARCHMENT("羊皮纸", 0xFFF6EBD2.toInt(), 0xFF302418.toInt()),
    IVORY("米白", 0xFFF6F1E5.toInt(), 0xFF34322C.toInt()),
    GREEN("护眼绿", 0xFFD8E5D3.toInt(), 0xFF2E3B30.toInt()),
    WHITE("纯白", 0xFFFAFAF8.toInt(), 0xFF282828.toInt()),
    NIGHT("夜间", 0xFF171A1D.toInt(), 0xFFB7B9B6.toInt())
}

internal enum class ReaderFont(val label: String, val css: String) {
    SANS("黑体", "sans-serif"), SERIF("宋体", "serif"), MONO("等宽", "monospace")
}

internal data class ReaderSettings(
    val fontSize: Float = 115f,
    val lineHeight: Float = 1.7f,
    val paragraphSpacing: Float = 0.6f,
    val letterSpacing: Float = 0f,
    val margins: Float = 1f,
    val font: ReaderFont = ReaderFont.SERIF,
    val bold: Boolean = false,
    val indent: Boolean = true,
    val justify: Boolean = true,
    val comfortableWidth: Boolean = true,
    val showProgress: Boolean = true,
    val paper: ReaderPaper = ReaderPaper.PARCHMENT,
    val lastDayPaper: ReaderPaper = ReaderPaper.PARCHMENT,
    val paperTexture: Float = 0.65f,
    val pageMode: ReaderPageMode = ReaderPageMode.SLIDE,
    val fullScreen: Boolean = true,
    val tapToTurn: Boolean = true,
    val volumeToTurn: Boolean = false,
    val brightnessGesture: Boolean = true,
    val pinchToResize: Boolean = true,
    val keepAwake: Boolean = true,
    val brightness: Float = -1f
) {
    fun normalized() = copy(
        fontSize = fontSize.bounded(80f, 220f, 115f),
        lineHeight = lineHeight.bounded(1.2f, 2.5f, 1.7f),
        paragraphSpacing = paragraphSpacing.bounded(0f, 2f, 0.6f),
        letterSpacing = letterSpacing.bounded(0f, 0.15f, 0f),
        margins = margins.bounded(0.4f, 2f, 1f),
        paperTexture = paperTexture.bounded(0f, 1f, 0.65f),
        lastDayPaper = if (paper != ReaderPaper.NIGHT) paper else lastDayPaper.takeUnless { it == ReaderPaper.NIGHT } ?: ReaderPaper.PARCHMENT,
        brightness = if (brightness == -1f) -1f else brightness.bounded(0.02f, 1f, -1f)
    )

    fun selectPaper(next: ReaderPaper) = copy(paper = next,
        lastDayPaper = if (paper != ReaderPaper.NIGHT) paper else lastDayPaper).normalized()

    fun toggleNight() = selectPaper(if (paper == ReaderPaper.NIGHT) lastDayPaper else ReaderPaper.NIGHT)

    /** Only properties which change character positions belong here. Colors never trigger pagination. */
    fun typographyKey() = listOf(fontSize, lineHeight, paragraphSpacing, letterSpacing, margins, font, bold, indent, justify)
}

internal enum class ReaderTypographyPreset(val label: String) {
    CLASSIC("经典"), RELAXED("舒展"), LARGE("大字"), COMPACT("紧凑");

    fun apply(value: ReaderSettings): ReaderSettings {
        val base = value.copy(letterSpacing = 0f, font = ReaderFont.SERIF, bold = false, indent = true, justify = true)
        return when (this) {
            CLASSIC -> base.copy(fontSize = 115f, lineHeight = 1.7f, paragraphSpacing = .6f, margins = 1f)
            RELAXED -> base.copy(fontSize = 120f, lineHeight = 1.9f, paragraphSpacing = .85f, margins = 1.1f)
            LARGE -> base.copy(fontSize = 150f, lineHeight = 1.65f, paragraphSpacing = .7f, margins = .75f, font = ReaderFont.SANS, justify = false)
            COMPACT -> base.copy(fontSize = 105f, lineHeight = 1.5f, paragraphSpacing = .4f, margins = .85f)
        }
    }

    fun matches(value: ReaderSettings) = apply(value).typographyKey() == value.typographyKey()
}

private fun Float.bounded(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback

/** Central 40% is always a menu target, including when edge page turns are disabled. */
internal fun readerTap(xFraction: Float, tapToTurn: Boolean, scrolling: Boolean, rtl: Boolean): Int = when {
    !tapToTurn || scrolling || xFraction in 0.3f..0.7f -> 0
    (xFraction < 0.3f) != rtl -> -1
    else -> 1
}

internal fun readerSwipe(dx: Float, dy: Float, threshold: Float, rtl: Boolean): Int = when {
    abs(dx) < threshold || abs(dx) < abs(dy) * 1.3f -> 0
    (dx < 0) != rtl -> 1
    else -> -1
}

/** Gesture navigation owns these physical edges; brightness and page turns must leave them alone. */
internal fun readerSystemBackEdge(x: Float, width: Int, left: Int, right: Int) =
    left > 0 && x < left || right > 0 && x > width - right

internal fun readerCommitSwipe(dx: Float, velocityX: Float, width: Int, slop: Float, minimumFling: Float, direction: Int): Boolean {
    val distance = -direction * dx
    val velocity = -direction * velocityX
    return distance > maxOf(slop * 2, width * 0.12f) || distance > slop && velocity > minimumFling
}
