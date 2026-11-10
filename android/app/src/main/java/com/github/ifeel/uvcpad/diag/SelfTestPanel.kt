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
import com.github.ifeel.uvcpad.R

/**
 * [uvcpad-t0-selftest] Non-interactive pre-flight panel (12 rows, refreshed once per second).
 *
 * Input transparency: not clickable/focusable and [onTouchEvent] returns false, so touches
 * pass through to the touch layer underneath. [stop] removes the refresh callback; the view
 * itself is removed by MainActivity when toggled off / destroyed → no listener survives.
 *
 * Extends [AppCompatTextView] (not [android.widget.TextView]) so the view keeps AppCompat's
 * tint/theme handling on every API level; this is required by AppCompatCustomView.
 */
class SelfTestPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private var provider: (() -> SelfTestSnapshot)? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(Color.argb(0xCC, 0, 0, 0))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        val pad = dp(8f)
        setPadding(pad, pad, pad, pad)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxWidth = dp(460f)
    }

    fun bind(provider: () -> SelfTestSnapshot) {
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
        val snapshot = provider?.invoke() ?: return
        text = context.getString(R.string.selftest_line, SelfTest.format(SelfTest.evaluate(snapshot)))
    }

    /**
     * Never consumes touches: events fall through to the touch layer.
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
