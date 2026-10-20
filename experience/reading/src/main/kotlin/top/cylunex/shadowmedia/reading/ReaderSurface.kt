package top.cylunex.shadowmedia.reading

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Intercept only recognized reading gestures; links, long-press selection and vertical scrolling stay in Readium. */
internal class ReaderSurface(context: Context) : FrameLayout(context) {
    val content = FragmentContainerView(context).apply { id = R.id.epub_reader }
    var settings = ReaderSettings()
    var gesturesEnabled = true
    var rtl = false
    var onTurn: (Boolean) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onBrightness: (Float) -> Unit = {}
    var onFontScale: (Float) -> Unit = {}
    var currentBrightness: () -> Float = { 0.5f }
    private val page = PageTurnOverlay(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var gesture = Gesture.NONE
    private var brightnessAtStart = 0.5f
    private var scaleFactor = 1f
    private var physicalForward = true
    private var multiTouch = false
    private enum class Gesture { NONE, PAGE, BRIGHTNESS, SCALE }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!gesturesEnabled || !settings.pinchToResize || page.busy) return false
            gesture = Gesture.SCALE; scaleFactor = 1f
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { onFontScale(scaleFactor) }
    })

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        page.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        page.onReveal = { progress, forward, mode ->
            content.alpha = 1f
            content.translationX = if (mode == ReaderPageMode.SLIDE) (if (forward) 1 else -1) * width * (1 - progress) else 0f
        }
        page.onClear = { content.alpha = 1f; content.translationX = 0f }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; downTime = event.eventTime
            gesture = Gesture.NONE; multiTouch = false
            brightnessAtStart = currentBrightness()
        }
        if (event.pointerCount > 1) {
            multiTouch = true
            if (gesture == Gesture.PAGE && !page.busy) { page.clear(); gesture = Gesture.NONE }
        }
        if (gesturesEnabled && !touchExploration()) scale.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (page.busy) return true
        if (!gesturesEnabled || touchExploration()) return false
        if (gesture != Gesture.NONE) return true
        if (multiTouch) return scale.isInProgress
        if (event.actionMasked != MotionEvent.ACTION_MOVE || event.eventTime - downTime > ViewConfiguration.getLongPressTimeout()) return false
        val dx = event.x - downX; val dy = event.y - downY
        if (settings.brightnessGesture && downX < width * 0.12f && abs(dy) > slop && abs(dy) > abs(dx) * 1.5f) {
            gesture = Gesture.BRIGHTNESS
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (settings.pageMode != ReaderPageMode.SCROLL && readerSwipe(dx, dy, slop, rtl) != 0) {
            physicalForward = dx < 0
            beginTurn(physicalForward)
            gesture = Gesture.PAGE
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (page.busy) return true
        when (gesture) {
            Gesture.BRIGHTNESS -> if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                onBrightness((brightnessAtStart + (downY - event.y) / height.coerceAtLeast(1)).coerceIn(0.02f, 1f))
            }
            Gesture.PAGE -> when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    page.drag(abs(event.x - downX) / width.coerceAtLeast(1))
                }
                MotionEvent.ACTION_UP -> {
                    val distance = event.x - downX
                    val committed = abs(distance) > maxOf(slop * 2, width * 0.12f) && (distance < 0) == physicalForward
                    if (committed) onTurn(physicalForward != rtl) else page.finish(false)
                }
                MotionEvent.ACTION_CANCEL -> page.finish(false)
            }
            Gesture.NONE -> if (event.actionMasked == MotionEvent.ACTION_UP && !multiTouch) performClick()
            Gesture.SCALE -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = Gesture.NONE
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onMenu()
        return true
    }

    fun prepareTurn(forward: Boolean): Boolean {
        if (page.busy) return false
        if (!page.hasPage) beginTurn(forward != rtl)
        page.busy = true
        return true
    }
    private fun beginTurn(physicalForward: Boolean) {
        setBackgroundColor(settings.paper.background)
        page.begin(content, settings.pageMode, physicalForward, settings.paper.background)
        if (page.hasPage) content.alpha = 0f
    }
    fun finishTurn(success: Boolean) = page.finish(success)
    fun cancelTurn() = page.clear()
    private fun touchExploration() = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
    override fun onDetachedFromWindow() { page.clear(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) page.clear()
    }
}

/** One bounded snapshot, released after every turn. Fold geometry and shading are drawn locally. */
internal class PageTurnOverlay(context: Context) : View(context) {
    private var snapshot: Bitmap? = null
    private var progress = 0f
    private var mode = ReaderPageMode.NONE
    private var forward = true
    private var paper = 0
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val shaderMatrix = Matrix()
    private val shadow = LinearGradient(0f, 0f, 1f, 0f, 0x40000000, 0, Shader.TileMode.CLAMP)
    private val backShade = LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x35000000, 0x08FFFFFF, 0x45000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    var busy = false
    var onReveal: (Float, Boolean, ReaderPageMode) -> Unit = { _, _, _ -> }
    var onClear: () -> Unit = {}
    val hasPage get() = snapshot != null

    fun begin(content: View, mode: ReaderPageMode, forward: Boolean, paper: Int) {
        clear()
        this.mode = mode; this.forward = forward; this.paper = paper
        if (mode == ReaderPageMode.NONE || mode == ReaderPageMode.SCROLL || width <= 0 || height <= 0) return
        val factor = min(1f, 1600f / maxOf(width, height))
        // A failed screenshot must never prevent navigation (including memory pressure).
        var captured: Bitmap? = null
        snapshot = try {
            Bitmap.createBitmap((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                captured = it
                val canvas = Canvas(it); canvas.drawColor(paper); canvas.scale(factor, factor); content.draw(canvas)
            }
        } catch (_: OutOfMemoryError) { captured?.recycle(); null }
        catch (_: RuntimeException) { captured?.recycle(); null }
        invalidate()
    }

    fun drag(value: Float) { progress = value.coerceIn(0f, 0.85f); invalidate() }
    fun finish(success: Boolean) {
        if (snapshot == null) { clear(); return }
        busy = true
        animator?.cancel()
        if (success) onReveal(progress, forward, mode)
        animator = ValueAnimator.ofFloat(progress, if (success) 1f else 0f).apply {
            duration = if (success) 280 else 180
            addUpdateListener {
                progress = it.animatedValue as Float
                if (success) onReveal(progress, forward, mode)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { clear() }
            })
            start()
        }
    }

    fun clear() {
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        snapshot?.recycle(); snapshot = null; progress = 0f; busy = false; onClear(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    private fun shade(shader: LinearGradient, start: Float, end: Float) {
        shaderMatrix.setScale(end - start, 1f)
        shaderMatrix.postTranslate(start, 0f)
        shader.setLocalMatrix(shaderMatrix)
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = snapshot ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val p = progress
        paint.shader = null; paint.colorFilter = null; paint.alpha = 255
        if (mode != ReaderPageMode.CURL) {
            val x = (if (forward) -1 else 1) * w * p
            canvas.save()
            canvas.translate(x, 0f)
            canvas.drawBitmap(bitmap, null, bounds, paint)
            if (mode == ReaderPageMode.COVER) {
                shade(shadow, if (forward) w else 0f, if (forward) w + 24f else -24f)
                canvas.drawRect(if (forward) w else -24f, 0f, if (forward) w + 24f else 0f, h, paint)
            }
            canvas.restore()
            return
        }
        // A curved fold, reflected page back, paper tint and two moving shadows.
        val edge = if (forward) w * (1 - p) else w * p
        val foldWidth = w * 0.22f * sin(Math.PI * p).toFloat()
        val bend = foldWidth * 0.24f
        val direction = if (forward) 1f else -1f
        path.reset()
        path.moveTo(if (forward) 0f else w, 0f)
        path.lineTo(edge, 0f)
        path.cubicTo(edge - direction * bend, h * 0.33f, edge - direction * bend, h * 0.67f, edge, h)
        path.lineTo(if (forward) 0f else w, h); path.close()
        canvas.save(); canvas.clipPath(path)
        canvas.drawBitmap(bitmap, null, bounds, paint)
        canvas.restore()
        val backEdge = edge - direction * foldWidth
        path.reset(); path.moveTo(edge, 0f)
        path.cubicTo(edge - direction * bend, h * 0.33f, edge - direction * bend, h * 0.67f, edge, h)
        path.lineTo(backEdge, h)
        path.cubicTo(backEdge - direction * bend, h * 0.67f, backEdge - direction * bend, h * 0.33f, backEdge, 0f)
        path.close()
        canvas.save(); canvas.clipPath(path)
        canvas.drawColor(paper)
        canvas.save(); canvas.translate(2 * edge, 0f); canvas.scale(-1f, 1f)
        paint.alpha = 35
        canvas.drawBitmap(bitmap, null, bounds, paint)
        canvas.restore(); paint.alpha = 255
        shade(backShade, backEdge, edge + direction)
        canvas.drawPath(path, paint); canvas.restore()
        if (p > 0 && p < 1) {
            shade(shadow, edge, edge + direction * 24f)
            canvas.drawRect(minOf(edge, edge + direction * 24), 0f, maxOf(edge, edge + direction * 24), h, paint)
        }
    }
}
