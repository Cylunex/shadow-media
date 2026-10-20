package top.cylunex.shadowmedia.reading

import kotlin.math.abs

internal enum class ReaderPageMode(val label: String) {
    CURL("仿真"), SLIDE("滑动"), COVER("覆盖"), NONE("无动画"), SCROLL("滚动")
}

internal enum class ReaderPaper(val label: String, val background: Int, val foreground: Int) {
    PARCHMENT("羊皮纸", 0xFFEAD7AE.toInt(), 0xFF403323.toInt()),
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
    val paper: ReaderPaper = ReaderPaper.PARCHMENT,
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
        brightness = if (brightness == -1f) -1f else brightness.bounded(0.02f, 1f, -1f)
    )
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
