package com.github.ifeel.uvcpad.diag

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

/**
 * [uvcpad-t0-hud] Debug-only heads-up display (semi-transparent black, small monospace,
 * top-right of the root layout, below the key bar).
 *
 * Input transparency is a hard requirement: this view is not clickable/focusable and
 * [onTouchEvent] always returns false, so a touch that lands on the HUD is re-dispatched
 * by the parent to the views below it (camera / touch layer) exactly as if it were not there.
 *
 * Extends [AppCompatTextView] (not [android.widget.TextView]) so the view keeps AppCompat's
 * tint/theme handling on every API level; this is required by AppCompatCustomView.
 */
class HudOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private var provider: (() -> HudModel)? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(Color.argb(0xB0, 0, 0, 0))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.MONOSPACE
        val pad = dp(6f)
        setPadding(pad, pad, pad, pad)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        Diag.ui("hud=created")
    }

    fun bind(provider: () -> HudModel) {
        this.provider = provider
        refresh()
    }

    fun start() {
        handler.removeCallbacks(tick)
        refresh()
        handler.postDelayed(tick, REFRESH_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    private fun refresh() {
        val model = provider?.invoke() ?: return
        text = HudText.format(model)
    }

    /**
     * Never consumes touches: events fall through to the underlying touch layer.
     *
     * Suppressed ClickableViewAccessibility on purpose: this view is a non-clickable pass-through
     * overlay that always returns false, so there is no click action a performClick() could report
     * to accessibility services — adding one would invent a fake clickable target.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    companion object {
        private const val REFRESH_MS = 1_000L
    }
}
