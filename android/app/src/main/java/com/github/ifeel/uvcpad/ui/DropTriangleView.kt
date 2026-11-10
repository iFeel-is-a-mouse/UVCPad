package com.github.ifeel.uvcpad.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Drop triangle (new in DESIGN §3.3): the only always-visible UI, top-center.
 *
 * Event exemption (M2 acceptance key): ACTION_DOWN is consumed (returns true) → the event stream belongs to the triangle,
 * does not penetrate into the touch gesture layer → tapping the triangle cannot be misinterpreted as a tap→left-click and produces no mouse reports.
 * ACTION_MOVE outside the hot zone (this View's bounds) marks the gesture cancelled; ACTION_UP inside the hot zone → [onToggle]().
 *
 * Visual: small triangle (solid black fill + white stroke, high-contrast e-ink scheme),
 * clearly visible on both light PC frames and dark e-ink screens;
 * hot zone = this View's bounds (48dp×48dp in the layout, ≥ design minimum, DESIGN §6.3).
 */
class DropTriangleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Lift callback inside the hot zone: MainActivity uses it to toggle the key bar (DESIGN §3.3) */
    var onToggle: (() -> Unit)? = null

    /**
     * [uvcpad-t0-hud] Long-press callback on the triangle. MainActivity wires it to the debug HUD
     * toggle only in debug builds; release leaves it null, so a long press behaves exactly like the
     * previous short tap (performClick → toggle key bar) after the finger lifts.
     */
    var onLongPress: (() -> Unit)? = null

    /** Set to true once ACTION_MOVE leaves the hot zone: ACTION_UP no longer triggers toggle */
    private var cancelled = false

    /** True once the long-press callback fired: the following ACTION_UP must not toggle the key bar */
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        if (!cancelled && onLongPress != null) {
            longPressFired = true
            onLongPress?.invoke()
        }
    }

    private val trianglePath = Path()

    /** Solid black fill: pure black has the highest contrast at e-ink 16-level grayscale and is the easiest to discern */
    private val triangleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    /** White stroke: outlines the shape on light PC frames (shadows are not rendered by the e-ink driver, so a stroke is used instead) */
    private val triangleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Triangle: 11dp wide × 5dp tall (about 1/3 of the original 32×16, user reported it as too big), top-center, pointing down (drop-down indicator)
        val tw = dp(11f)
        val th = dp(5f)
        val cx = w / 2f
        val top = dp(8f)
        trianglePath.reset()
        trianglePath.moveTo(cx - tw / 2f, top)
        trianglePath.lineTo(cx + tw / 2f, top)
        trianglePath.lineTo(cx, top + th)
        trianglePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Stroke first, then fill: the white edge stays outside the solid black triangle, clear on both light and dark backgrounds
        canvas.drawPath(trianglePath, triangleStrokePaint)
        canvas.drawPath(trianglePath, triangleFillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelled = false
                longPressFired = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true // Consume: the event stream belongs to the triangle, does not penetrate the touch layer
            }
            MotionEvent.ACTION_MOVE -> {
                // Event ownership is decided by ACTION_DOWN: after leaving the hot zone, MOVE still dispatches to this layer; mark cancelled
                if (!isInsideHotZone(event.x, event.y)) cancelled = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val trigger = !cancelled && !longPressFired
                cancelled = false
                longPressFired = false
                if (trigger) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                cancelled = true
                longPressFired = false
                return true
            }
        }
        return true
    }

    /**
     * [uvcpad-lint] ClickableViewAccessibility: a tap inside the hot zone is a real click, so route
     * it through performClick() (announces the accessibility click) and forward to [onToggle].
     */
    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    /** Hot zone = this View's bounds (48dp×48dp) */
    private fun isInsideHotZone(x: Float, y: Float): Boolean =
        x in 0f..width.toFloat() && y in 0f..height.toFloat()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LONG_PRESS_MS = 600L
    }
}
