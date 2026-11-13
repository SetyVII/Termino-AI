package com.termino.core.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * FrameLayout raíz del lector que intercepta los swipes horizontales a nivel
 * superior, antes de que el NestedScrollView o el TextView (selección de
 * texto) puedan robarse el gesto.
 *
 * - No intercepta en ACTION_DOWN: el gesto empieza a cederse a los hijos, así
 *   que el scroll vertical, el long-press y los taps siguen funcionando.
 * - Solo reclama el gesto cuando el movimiento es claramente horizontal
 *   (doble touchSlop y dominio horizontal 1.5x) y rápido (< 600 ms), y solo
 *   si [canSwipe] lo permite (p. ej. sin selección de texto activa).
 * - Una vez interceptado, consume los MOVE y, en el UP, dispara
 *   [onSwipeLeft] (dedo a la izquierda = página siguiente) o [onSwipeRight]
 *   (página anterior) según el desplazamiento total.
 */
class ReaderGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Dedo hacia la izquierda: página siguiente. */
    var onSwipeLeft: (() -> Unit)? = null

    /** Dedo hacia la derecha: página anterior. */
    var onSwipeRight: (() -> Unit)? = null

    /** Si devuelve false no se intercepta ningún gesto (p. ej. hay selección activa). */
    var canSwipe: (() -> Boolean)? = null

    /** Pellizco con dos dedos: inicio (foco), factor acumulado y final. */
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
        // Segundo dedo: el gesto pasa a ser un pellizco y se le quita al texto
        // (que recibe CANCEL, sin selección ni scroll accidental).
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
