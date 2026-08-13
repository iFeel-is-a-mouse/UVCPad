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

    /** Set to true once ACTION_MOVE leaves the hot zone: ACTION_UP no longer triggers toggle */
    private var cancelled = false

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
                return true // Consume: the event stream belongs to the triangle, does not penetrate the touch layer
            }
            MotionEvent.ACTION_MOVE -> {
                // Event ownership is decided by ACTION_DOWN: after leaving the hot zone, MOVE still dispatches to this layer; mark cancelled
                if (!isInsideHotZone(event.x, event.y)) cancelled = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val trigger = !cancelled
                cancelled = false
                if (trigger) onToggle?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelled = true
                return true
            }
        }
        return true
    }

    /** Hot zone = this View's bounds (48dp×48dp) */
    private fun isInsideHotZone(x: Float, y: Float): Boolean =
        x in 0f..width.toFloat() && y in 0f..height.toFloat()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
