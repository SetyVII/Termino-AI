package com.termino.core.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root FrameLayout of the reader that intercepts horizontal swipes at the top
 * level, before the NestedScrollView or the TextView (text selection) can
 * steal the gesture.
 *
 * - It does not intercept on ACTION_DOWN: the gesture starts out going to the
 *   children, so vertical scroll, long-press and taps keep working.
 * - It only claims the gesture when the movement is clearly horizontal
 *   (twice the touch slop and 1.5x horizontal dominance) and fast (< 600 ms),
 *   and only if [canSwipe] allows it (e.g. no active text selection).
 * - Once intercepted, it consumes MOVE events and on UP fires [onSwipeLeft]
 *   (finger to the left = next page) or [onSwipeRight] (previous page)
 *   based on the total displacement.
 */
class ReaderGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Finger moving left: next page. */
    var onSwipeLeft: (() -> Unit)? = null

    /** Finger moving right: previous page. */
    var onSwipeRight: (() -> Unit)? = null

    /** When it returns false no gesture is intercepted (e.g. an active selection). */
    var canSwipe: (() -> Boolean)? = null

    /** Two-finger pinch: start (focus), accumulated factor and end. */
    var onPinchStart: ((focusX: Float, focusY: Float) -> Unit)? = null
    var onPinch: ((factor: Float) -> Unit)? = null
    var onPinchEnd: ((factor: Float) -> Unit)? = null

    private var pinchMode = false
    private var pinchFactor = 1f
    private var pinchStarted = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchFactor = 1f
                pinchStarted = true
                onPinchStart?.invoke(detector.focusX, detector.focusY)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchFactor = (pinchFactor * detector.scaleFactor).coerceIn(0.4f, 2.6f)
                onPinch?.invoke(pinchFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                finishPinch()
            }
        }
    ).apply { isQuickScaleEnabled = false }

    private fun finishPinch() {
        if (pinchStarted) {
            pinchStarted = false
            onPinchEnd?.invoke(pinchFactor)
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = dp(64)

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var intercepting = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (onPinch != null) scaleDetector.onTouchEvent(ev)
        // Second finger: the gesture becomes a pinch and is taken from the text
        // (which receives CANCEL, so no accidental selection or scroll).
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount >= 2 && onPinch != null) {
            pinchMode = true
            intercepting = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = System.currentTimeMillis()
                intercepting = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepting && canSwipe?.invoke() != false) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val elapsed = System.currentTimeMillis() - downTime
                    if (abs(dx) > touchSlop * 2 &&
                        abs(dx) > abs(dy) * 1.5f &&
                        elapsed < 600L
                    ) {
                        intercepting = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pinchMode) {
            scaleDetector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                finishPinch()
                pinchMode = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (intercepting) return true
                return super.onTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (intercepting) {
                    val dx = ev.x - downX
                    if (dx < -swipeThreshold) {
                        onSwipeLeft?.invoke()
                    } else if (dx > swipeThreshold) {
                        onSwipeRight?.invoke()
                    }
                    resetGesture()
                    return true
                }
                return super.onTouchEvent(ev)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (intercepting) {
                    resetGesture()
                    return true
                }
                return super.onTouchEvent(ev)
            }
            else -> return super.onTouchEvent(ev)
        }
    }

    private fun resetGesture() {
        intercepting = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
