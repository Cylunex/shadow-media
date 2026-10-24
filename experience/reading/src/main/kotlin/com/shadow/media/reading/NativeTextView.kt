package com.shadow.media.reading

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
import android.view.VelocityTracker
import androidx.core.view.ViewCompat
import androidx.core.graphics.withClip
import androidx.core.view.WindowInsetsCompat
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import kotlinx.coroutines.*
import com.shadow.media.reading.legado.LegadoPageCurl
import kotlin.math.abs

/** Three real Canvas pages, including while dragging. No browser or document screenshot. */
internal class NativeTextView(context: Context) : View(context) {
    var gesturesEnabled = true
        set(value) { if (field && !value) pause(); field = value }
    var onMenu: () -> Unit = {}
    var onBoundary: (Int) -> Unit = {}
    var onPosition: (Int, Boolean) -> Unit = { _, _ -> }
    var onReady: () -> Unit = {}
    var onError: (String) -> Unit = {}
    var onSettings: (ReaderSettings) -> Unit = {}
    var onExcerpt: (String, Int) -> Unit = { _, _ -> }
    var onBrightnessPreview: (Float?) -> Unit = {}
    private var touchActive = false
    private var gestureCancelled = false
    private var boundaryPending = false
    private var edgeDragX = 0f
    private var edgeAnimation: ValueAnimator? = null
    private var notice: String? = null
    private val clearNotice = Runnable { notice = null; invalidate() }
    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fonts = ReaderFonts(context)
    private val scrollStride get() = (height - 2 * (document?.topInset ?: 0f)).coerceAtLeast(1f)
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
    private var paperTexture: Bitmap? = null
    private val curl = LegadoPageCurl()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var scaling = false
    private var multiTouch = false
    private var longPress = false
    private var systemEdge = false
    private var velocity: VelocityTracker? = null
    private var brightnessDrag = false
    private var gestureBrightness = .5f
    private var pinchFont = 115f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!settings.pinchToResize) return false
            scaling = true; pinchFont = settings.fontSize; stopMotion(); return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean { pinchFont = (pinchFont * detector.scaleFactor).coerceIn(80f, 220f); showNotice("字号 ${(pinchFont / 5).toInt()} sp"); return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { if (!gestureCancelled) onSettings(settings.copy(fontSize = pinchFont)) }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!touchActive || !gesturesEnabled || systemEdge || multiTouch || longPress || scaling || dragging || brightnessDrag) return true
            val direction = readerTap(e.x / width.coerceAtLeast(1), settings.tapToTurn, settings.pageMode == ReaderPageMode.SCROLL, false)
            if (direction == 0) onMenu() else turn(direction)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!touchActive || !gesturesEnabled || systemEdge || multiTouch || dragging || scaling || brightnessDrag) return
            longPress = true
            val doc = document ?: return
            if (e.x < doc.margin || e.x > width - doc.margin || e.y < doc.topInset || e.y > height - doc.topInset) return
            val line = doc.pages[pageIndex].lines.minByOrNull { abs(it.baseline - (e.y + scrollOffset)) } ?: return
            if (settings.haptics) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val text = doc.chapter.text
            val start = text.lastIndexOf('\n', (line.start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOf('\n', line.end).let { if (it < 0) text.length else it }
            onExcerpt(text.substring(start, end).trim(), line.start)
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!touchActive || overScroll != 0f || settings.pageMode != ReaderPageMode.SCROLL || systemEdge || multiTouch || longPress || scaling || brightnessDrag) return false
            scrollLastY = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, -height * 4, height * 4)
            postInvalidateOnAnimation(); return true
        }
    })
    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun show(chapter: NativeTextChapter, title: String, offset: Int, value: ReaderSettings) {
        cancelTurn(); boundaryPending = false
        this.chapter = chapter; bookTitle = title; readingOffset = safeTextOffset(chapter.text, offset)
        settings = value
        reflow()
    }
    fun configure(value: ReaderSettings) {
        if (value == settings) return
        val previous = settings
        val reflow = layoutKey(previous) != layoutKey(value)
        settings = value
        if (reflow) { cancelTurn(); reflow() }
        else {
            if (previous.paper != value.paper || previous.paperTexture != value.paperTexture) { pause(); clearBitmaps() }
            if (previous.pageMode != value.pageMode) seek(readingOffset, user = false)
            invalidate()
        }
    }
    private fun layoutKey(s: ReaderSettings) = s.typographyKey() + listOf(s.comfortableWidth, s.showProgress)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); if (w != oldw || h != oldh) reflow() }
    private fun reflow() {
        val content = chapter ?: return
        if (width <= 0 || height <= 0) return
        layoutJob?.cancel()
        val w = width; val h = height; val style = settings
        layoutJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    ReaderPaperTexture.bitmap(context.applicationContext)
                    NativeTextLayout.paginate(content, w, h, style, resources.displayMetrics.density, resources.displayMetrics.scaledDensity, fonts.typeface(style))
                }
                paperTexture = ReaderPaperTexture.bitmap(context.applicationContext)
                cancelTurn(); clearBitmaps(); document = result
                pageIndex = result.pageAt(readingOffset)
                if (settings.pageMode == ReaderPageMode.SCROLL && readingOffset > 0) {
                    val line = result.pages[pageIndex].lines.lastOrNull { it.start <= readingOffset }
                    scrollOffset = if (pageIndex == result.pages.lastIndex) 0f else ((line?.baseline ?: result.topInset) - result.topInset).coerceIn(0f, scrollStride - 1f)
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
            scrollOffset = if (pageIndex == doc.pages.lastIndex) 0f else ((doc.pages[pageIndex].lines.lastOrNull { it.start <= readingOffset }?.baseline ?: doc.topInset) - doc.topInset).coerceIn(0f, scrollStride - 1f)
        }
        pruneBitmaps(); updateDescription(); invalidate(); onPosition(readingOffset, user)
    }
    private fun changePage(target: Int) {
        val doc = document ?: return
        if (target !in doc.pages.indices) { requestBoundary(if (target < 0) -1 else 1); return }
        pageIndex = target; readingOffset = doc.pages[target].start
        pruneBitmaps(); updateDescription(); onPosition(readingOffset, true); invalidate()
    }
    fun turn(direction: Int) {
        val doc = document ?: return
        if (!isEnabled || boundaryPending || layoutJob?.isActive == true || animation != null) return
        scroller.abortAnimation()
        if (pageIndex + direction !in doc.pages.indices) { requestBoundary(direction); return }
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
    private fun stopMotion() {
        animation?.cancel(); animation = null; edgeAnimation?.cancel(); edgeAnimation = null
        scroller.abortAnimation(); overScroll = 0f; edgeDragX = 0f; turnProgress = 0f; turnDirection = 0; invalidate()
    }
    fun pause() {
        gestureCancelled = true; touchActive = false
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0).also {
            gestures.onTouchEvent(it); scale.onTouchEvent(it); it.recycle()
        }
        if (brightnessDrag) onBrightnessPreview(null)
        brightnessDrag = false; dragging = false; velocity?.recycle(); velocity = null
        stopMotion()
    }
    fun boundaryResolved(message: String? = null) { boundaryPending = false; pause(); message?.let(::showNotice) }
    private fun requestBoundary(direction: Int) {
        if (boundaryPending) return
        pause(); boundaryPending = true
        if (settings.haptics) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onBoundary(direction)
    }
    fun showNotice(message: String) {
        notice = message; removeCallbacks(clearNotice); postDelayed(clearNotice, 1400); invalidate()
    }
    private fun drawNotice(canvas: Canvas) {
        val text = notice ?: return
        feedbackPaint.textSize = 13 * resources.displayMetrics.scaledDensity
        val padding = 14 * resources.displayMetrics.density
        val w = feedbackPaint.measureText(text) + padding * 2
        val top = height - 104 * resources.displayMetrics.density
        feedbackPaint.color = settings.paper.background; feedbackPaint.alpha = 240
        canvas.drawRoundRect((width - w) / 2, top, (width + w) / 2, top + padding * 2.5f, padding, padding, feedbackPaint)
        feedbackPaint.color = settings.paper.foreground; feedbackPaint.alpha = 255
        canvas.drawText(text, (width - w) / 2 + padding, top + padding * 1.55f, feedbackPaint)
    }
    private fun rebound() {
        notice = null; removeCallbacks(clearNotice)
        val x = edgeDragX; val y = overScroll
        edgeAnimation?.cancel()
        edgeAnimation = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160
            addUpdateListener { val fraction = it.animatedValue as Float; edgeDragX = x * fraction; overScroll = y * fraction; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { edgeAnimation = null; edgeDragX = 0f; overScroll = 0f; invalidate() }
            }); start()
        }
    }
    fun cancelTurn() { pause(); scrollOffset = 0f }
    private fun bitmap(page: Int): Bitmap = bitmaps.getOrPut(page) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { document!!.draw(Canvas(it), page, bookTitle, paperTexture, settings) }
    }
    private fun clearBitmaps() { bitmaps.values.forEach { it.recycle() }; bitmaps.clear() }
    private fun pruneBitmaps() { bitmaps.keys.filter { abs(it - pageIndex) > 1 }.forEach { bitmaps.remove(it)?.recycle() } }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(settings.paper.background)
        val doc = document ?: return
        if (doc.width != width || doc.height != height) return
        val current = bitmap(pageIndex)
        if (settings.pageMode == ReaderPageMode.SCROLL) {
            drawReaderPaper(canvas, width.toFloat(), height.toFloat(), settings, paperTexture)
            val viewportWidth = width.toFloat(); val viewportHeight = height.toFloat()
            canvas.withClip(0f, doc.topInset, viewportWidth, viewportHeight - doc.topInset) {
                val pull = (-overScroll * .22f).coerceIn(-viewportHeight * .08f, viewportHeight * .08f)
                for (index in (pageIndex - 1)..(pageIndex + 1)) if (index in doc.pages.indices) {
                    val y = (index - pageIndex) * scrollStride - scrollOffset + pull
                    withClip(0f, doc.topInset + y, viewportWidth, viewportHeight - doc.topInset + y) {
                        drawBitmap(bitmap(index), 0f, y, null)
                    }
                }
            }
            doc.drawFurniture(canvas, pageIndex, bookTitle, settings)
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
        } else canvas.drawBitmap(current, edgeDragX, 0f, null)
        drawNotice(canvas)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !gesturesEnabled || document == null || layoutJob?.isActive == true || boundaryPending) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (animation != null) { touchActive = false; return true }
            stopMotion(); touchActive = true; gestureCancelled = false
            downX = event.x; downY = event.y; lastY = event.y
            dragging = false; scaling = false; brightnessDrag = false; multiTouch = false; longPress = false
            val insets = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
            systemEdge = readerSystemBackEdge(downX, width, insets?.left ?: 0, insets?.right ?: 0)
            gestureBrightness = settings.brightness.takeIf { it >= 0 } ?: (
                android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
            velocity?.recycle(); velocity = VelocityTracker.obtain()
        }
        if (!touchActive) return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) gestureCancelled = true
        if (systemEdge) {
            if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) pause()
            return true
        }
        velocity?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { multiTouch = true; dragging = false; stopMotion() }
        scale.onTouchEvent(event)
        if (!multiTouch) gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (scaling || multiTouch || longPress) return true
                val dx = event.x - downX; val dy = event.y - downY
                if (!dragging && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.6 && downX < 24 * resources.displayMetrics.density && settings.brightnessGesture) brightnessDrag = true
                if (brightnessDrag) {
                    val brightness = (gestureBrightness - dy / height).coerceIn(.02f, 1f)
                    onBrightnessPreview(brightness); showNotice("亮度 ${(brightness * 100).toInt()}%")
                    return true
                }
                if (settings.pageMode == ReaderPageMode.SCROLL) {
                    if (!dragging && abs(dy) > slop && abs(dy) > abs(dx) * 1.3f) dragging = true
                    if (dragging) scrollByPixels(lastY - event.y, fromTouch = true)
                    lastY = event.y
                } else {
                    val direction = readerSwipe(dx, dy, slop.toFloat(), false)
                    if (!dragging && direction != 0) dragging = true
                    if (dragging) {
                        if (direction != 0) turnDirection = direction
                        if (pageIndex + turnDirection in document!!.pages.indices) {
                            edgeDragX = 0f
                            turnProgress = (-turnDirection * dx / width).coerceIn(0f, .999f)
                        } else {
                            turnProgress = 0f
                            edgeDragX = (dx * .22f).coerceIn(-width * .08f, width * .08f)
                            showNotice(if (turnDirection > 0) "松手进入下一章" else "松手进入上一章")
                        }
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (brightnessDrag) {
                    onSettings(settings.copy(brightness = (gestureBrightness - (event.y - downY) / height).coerceIn(.02f, 1f)))
                    brightnessDrag = false
                } else if (dragging && !multiTouch && !longPress && !scaling) {
                    if (settings.pageMode == ReaderPageMode.SCROLL) {
                        if (abs(overScroll) > height * .15f) requestBoundary(if (overScroll > 0) 1 else -1)
                        else if (overScroll != 0f) rebound()
                    } else {
                        velocity?.computeCurrentVelocity(1000)
                        val commit = readerCommitSwipe(event.x - downX, velocity?.xVelocity ?: 0f, width, slop.toFloat(),
                            ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat(), turnDirection)
                        if (pageIndex + turnDirection !in document!!.pages.indices) {
                            if (commit) requestBoundary(turnDirection) else rebound()
                        } else if (settings.pageMode == ReaderPageMode.NONE) {
                            val target = pageIndex + turnDirection; stopMotion(); if (commit) changePage(target)
                        } else animateTurn(commit)
                    }
                }
                velocity?.recycle(); velocity = null; touchActive = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pause(); rebound() }
        }
        return true
    }
    private fun scrollByPixels(delta: Float, fromTouch: Boolean = false) {
        val doc = document ?: return
        var dy = delta
        if (fromTouch && overScroll != 0f) {
            val combined = overScroll + dy
            if ((combined > 0) == (overScroll > 0)) { overScroll = combined; invalidate(); return }
            dy = combined; overScroll = 0f
        }
        scrollOffset += dy
        while (scrollOffset >= scrollStride && pageIndex < doc.pages.lastIndex) { scrollOffset -= scrollStride; changePage(pageIndex + 1) }
        while (scrollOffset < 0 && pageIndex > 0) { scrollOffset += scrollStride; changePage(pageIndex - 1) }
        if (pageIndex == 0 && scrollOffset < 0 || pageIndex == doc.pages.lastIndex && scrollOffset > 0) {
            if (fromTouch) {
                overScroll += scrollOffset
                showNotice(if (overScroll > 0) "继续上拉，松手进入下一章" else "继续下拉，松手进入上一章")
            } else scroller.abortAnimation()
            scrollOffset = 0f; invalidate(); return
        }
        val newOffset = doc.offsetAt(pageIndex, scrollOffset)
        if (newOffset != readingOffset) { readingOffset = newOffset; updateDescription(); onPosition(readingOffset, true) }
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
    fun dispose() { removeCallbacks(clearNotice); cancelTurn(); velocity?.recycle(); velocity = null; scope.cancel(); clearBitmaps(); document = null }
}
