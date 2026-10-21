package top.cylunex.shadowmedia.reading

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import kotlinx.coroutines.*
import top.cylunex.shadowmedia.reading.legado.LegadoPageCurl
import kotlin.math.abs

/** Three real Canvas pages, including while dragging. No browser or document screenshot. */
internal class NativeTextView(context: Context) : View(context) {
    var onMenu: () -> Unit = {}
    var onBoundary: (Int) -> Unit = {}
    var onPosition: (Int, Boolean) -> Unit = { _, _ -> }
    var onReady: () -> Unit = {}
    var onError: (String) -> Unit = {}
    var onSettings: (ReaderSettings) -> Unit = {}
    var onExcerpt: (String, Int) -> Unit = { _, _ -> }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var layoutJob: Job? = null
    private var chapter: NativeTextChapter? = null
    private var bookTitle = ""
    private var settings = ReaderSettings()
    internal var document: NativeTextLayout? = null; private set
    internal var pageIndex = 0; private set
    var readingOffset = 0; private set
    private var turnProgress = 0f
    private var turnDirection = 0
    private var scrollOffset = 0f
    private var scrollLastY = 0
    private var overScroll = 0f
    private val scroller = OverScroller(context)
    private var animation: ValueAnimator? = null
    private val bitmaps = mutableMapOf<Int, Bitmap>()
    private val curl = LegadoPageCurl()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var scaling = false
    private var brightnessDrag = false
    private var gestureBrightness = .5f
    private var pinchFont = 115f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!settings.pinchToResize) return false
            scaling = true; pinchFont = settings.fontSize; cancelTurn(); return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean { pinchFont = (pinchFont * detector.scaleFactor).coerceIn(80f, 220f); return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { onSettings(settings.copy(fontSize = pinchFont)) }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (scaling || dragging || brightnessDrag) return true
            val direction = readerTap(e.x / width.coerceAtLeast(1), settings.tapToTurn, settings.pageMode == ReaderPageMode.SCROLL, false)
            if (direction == 0) onMenu() else turn(direction)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (dragging || scaling) return
            val doc = document ?: return
            val line = doc.pages[pageIndex].lines.minByOrNull { abs(it.baseline - (e.y + scrollOffset)) } ?: return
            val text = doc.chapter.text
            val start = text.lastIndexOf('\n', (line.start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOf('\n', line.end).let { if (it < 0) text.length else it }
            onExcerpt(text.substring(start, end).trim(), line.start)
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (settings.pageMode != ReaderPageMode.SCROLL || scaling || brightnessDrag) return false
            scrollLastY = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, -height * 4, height * 4)
            postInvalidateOnAnimation(); return true
        }
    })
    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun show(chapter: NativeTextChapter, title: String, offset: Int, value: ReaderSettings) {
        cancelTurn()
        this.chapter = chapter; bookTitle = title; readingOffset = safeTextOffset(chapter.text, offset)
        settings = value
        reflow()
    }
    fun configure(value: ReaderSettings) {
        if (value == settings) return
        val reflow = layoutKey(settings) != layoutKey(value)
        settings = value
        if (reflow) { cancelTurn(); reflow() }
        else invalidate()
    }
    private fun layoutKey(s: ReaderSettings) = listOf(s.fontSize, s.lineHeight, s.paragraphSpacing, s.letterSpacing, s.margins, s.font, s.bold, s.indent, s.paper)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); if (w != oldw || h != oldh) reflow() }
    private fun reflow() {
        val content = chapter ?: return
        if (width <= 0 || height <= 0) return
        layoutJob?.cancel()
        val w = width; val h = height; val style = settings
        layoutJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { NativeTextLayout.paginate(content, w, h, style, resources.displayMetrics.density, resources.displayMetrics.scaledDensity) }
                cancelTurn(); clearBitmaps(); document = result
                pageIndex = result.pageAt(readingOffset)
                if (style.pageMode == ReaderPageMode.SCROLL && readingOffset > 0) {
                    val line = result.pages[pageIndex].lines.lastOrNull { it.start <= readingOffset }
                    scrollOffset = ((line?.baseline ?: 48f * resources.displayMetrics.density) - 48f * resources.displayMetrics.density).coerceAtLeast(0f)
                }
                updateDescription(); invalidate(); onPosition(readingOffset, false); onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "文本排版失败") }
        }
    }
    fun seek(offset: Int, user: Boolean = true) {
        val doc = document ?: return
        cancelTurn(); readingOffset = safeTextOffset(doc.chapter.text, offset); pageIndex = doc.pageAt(readingOffset)
        if (settings.pageMode == ReaderPageMode.SCROLL && readingOffset > 0) {
            scrollOffset = ((doc.pages[pageIndex].lines.lastOrNull { it.start <= readingOffset }?.baseline ?: 0f) - 48f * resources.displayMetrics.density).coerceAtLeast(0f)
        }
        pruneBitmaps(); updateDescription(); invalidate(); onPosition(readingOffset, user)
    }
    private fun changePage(target: Int) {
        val doc = document ?: return
        if (target !in doc.pages.indices) { onBoundary(if (target < 0) -1 else 1); return }
        pageIndex = target; readingOffset = doc.pages[target].start
        pruneBitmaps(); updateDescription(); onPosition(readingOffset, true); invalidate()
    }
    fun turn(direction: Int) {
        val doc = document ?: return
        if (!isEnabled || layoutJob?.isActive == true || animation != null) return
        scroller.abortAnimation()
        if (pageIndex + direction !in doc.pages.indices) { onBoundary(direction); return }
        if (settings.pageMode == ReaderPageMode.NONE || settings.pageMode == ReaderPageMode.SCROLL) { cancelTurn(); changePage(pageIndex + direction); return }
        turnDirection = direction; animateTurn(true)
    }
    private fun animateTurn(complete: Boolean) {
        val start = turnProgress
        animation?.cancel()
        val direction = turnDirection
        animation = ValueAnimator.ofFloat(start, if (complete) 1f else 0f).apply {
            duration = (240 * abs((if (complete) 1f else 0f) - start)).toLong().coerceAtLeast(60)
            addUpdateListener { turnProgress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    this@NativeTextView.animation = null; turnProgress = 0f; turnDirection = 0
                    if (!cancelled && complete) changePage(pageIndex + direction)
                    invalidate()
                }
            })
            start()
        }
    }
    fun pause() { animation?.cancel(); animation = null; scroller.abortAnimation(); overScroll = 0f; turnProgress = 0f; turnDirection = 0; invalidate() }
    fun cancelTurn() { pause(); scrollOffset = 0f }
    private fun bitmap(page: Int): Bitmap = bitmaps.getOrPut(page) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { document!!.draw(Canvas(it), page, bookTitle) }
    }
    private fun clearBitmaps() { bitmaps.values.forEach { it.recycle() }; bitmaps.clear() }
    private fun pruneBitmaps() { bitmaps.keys.filter { abs(it - pageIndex) > 1 }.forEach { bitmaps.remove(it)?.recycle() } }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(settings.paper.background)
        val doc = document ?: return
        if (doc.width != width || doc.height != height) return
        val current = bitmap(pageIndex)
        if (settings.pageMode == ReaderPageMode.SCROLL) {
            for (index in (pageIndex - 1)..(pageIndex + 1)) if (index in doc.pages.indices) canvas.drawBitmap(bitmap(index), 0f, (index - pageIndex) * height - scrollOffset, null)
        } else if (turnProgress > 0 && pageIndex + turnDirection in doc.pages.indices) {
            val adjacent = bitmap(pageIndex + turnDirection)
            when (settings.pageMode) {
                ReaderPageMode.CURL -> curl.draw(canvas, current, adjacent, turnProgress, turnDirection > 0, settings.paper.background)
                ReaderPageMode.SLIDE -> {
                    val x = -turnDirection * width * turnProgress
                    canvas.drawBitmap(current, x, 0f, null); canvas.drawBitmap(adjacent, x + turnDirection * width, 0f, null)
                }
                else -> {
                    val x = if (turnDirection > 0) -width * turnProgress else -width * (1f - turnProgress)
                    canvas.drawBitmap(if (turnDirection > 0) adjacent else current, 0f, 0f, null)
                    canvas.drawBitmap(if (turnDirection > 0) current else adjacent, x, 0f, null)
                    val edge = x + width
                    val shadow = Paint().apply { shader = LinearGradient(edge, 0f, edge + 20, 0f, 0x44000000, 0, Shader.TileMode.CLAMP) }
                    canvas.drawRect(edge, 0f, edge + 20, height.toFloat(), shadow)
                }
            }
        } else canvas.drawBitmap(current, 0f, 0f, null)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || document == null || layoutJob?.isActive == true) return true
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animation?.cancel(); scroller.abortAnimation(); downX = event.x; downY = event.y; lastY = event.y
                dragging = false; scaling = false; brightnessDrag = false
                gestureBrightness = settings.brightness.takeIf { it >= 0 } ?: .5f
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaling) return true
                val dx = event.x - downX; val dy = event.y - downY
                if (!dragging && abs(dy) > slop && abs(dy) > abs(dx) * 1.3 && downX < width * .07f && settings.brightnessGesture) brightnessDrag = true
                if (brightnessDrag) { onSettings(settings.copy(brightness = (gestureBrightness - dy / height).coerceIn(.02f, 1f))); return true }
                if (settings.pageMode == ReaderPageMode.SCROLL) {
                    if (abs(dy) > slop) dragging = true
                    if (dragging) scrollByPixels(lastY - event.y)
                    lastY = event.y
                } else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                    dragging = true; val direction = if (dx < 0) 1 else -1
                    if (pageIndex + direction in document!!.pages.indices) { turnDirection = direction; turnProgress = (abs(dx) / width).coerceIn(0f, .999f); invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && !scaling && !brightnessDrag && settings.pageMode != ReaderPageMode.SCROLL) {
                    if (turnDirection != 0) animateTurn(turnProgress > .15f)
                    else if (abs(event.x - downX) > width * .15f) onBoundary(if (event.x < downX) 1 else -1)
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { cancelTurn(); dragging = false }
        }
        return true
    }
    private fun scrollByPixels(dy: Float) {
        val doc = document ?: return
        // Keep the visible line as the anchor even when the viewport has not crossed a page.
        scrollOffset += dy
        while (scrollOffset >= height && pageIndex < doc.pages.lastIndex) { scrollOffset -= height; changePage(pageIndex + 1) }
        while (scrollOffset < 0 && pageIndex > 0) { scrollOffset += height; changePage(pageIndex - 1) }
        val atStart = pageIndex == 0 && scrollOffset < 0
        val atEnd = pageIndex == doc.pages.lastIndex && scrollOffset > 0
        if (atStart || atEnd) {
            overScroll += scrollOffset
            scrollOffset = 0f
            if (abs(overScroll) > height * .15f) {
                val direction = if (overScroll > 0) 1 else -1
                overScroll = 0f; scroller.abortAnimation(); onBoundary(direction)
                return
            }
        } else overScroll = 0f
        val newOffset = doc.offsetAt(pageIndex, scrollOffset)
        if (newOffset != readingOffset) { readingOffset = newOffset; onPosition(readingOffset, true) }
        invalidate()
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { val y = scroller.currY; scrollByPixels((y - scrollLastY).toFloat()); scrollLastY = y; postInvalidateOnAnimation() }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun updateDescription() {
        val doc = document ?: return
        contentDescription = "${doc.chapter.ref.title}，第 ${pageIndex + 1} 页，共 ${doc.pages.size} 页。" + doc.chapter.text.substring(doc.pages[pageIndex].start, doc.pages[pageIndex].end)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "阅读菜单"))
        info.isScrollable = true
    }
    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> { onMenu(); true }
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { turn(1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { turn(-1); true }
        else -> super.performAccessibilityAction(action, args)
    }
    fun dispose() { cancelTurn(); scope.cancel(); clearBitmaps(); document = null }
}
