package com.github.ifeel.uvcpad.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * Key bar container (new in DESIGN §3.4): horizontal top bar (following the hdmi2mp topOverlay style).
 *
 * Area event consumption (M2 acceptance key): every touch inside the key bar bounds is consumed and never reaches the
 * touch gesture layer —
 * - buttons are individually clickable, DOWN is consumed by the button → tapping a menu button produces no mouse report;
 * - non-button areas are consumed by this container's [onTouchEvent] (returns true) → tapping the menu gaps also does not penetrate.
 *
 * Outside the bar, while expanded, it is still a touchpad: this container only consumes events within its own bounds;
 * touches outside the bar reach the touch layer normally.
 */
class KeyBarPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Callback invoked when a touch on a non-button area is consumed (MainActivity uses it to reset the auto-hide timer, DESIGN §3.4) */
    var onAreaTouch: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onAreaTouch?.invoke()
        }
        return true // Consume all touches in the key bar area (buttons consume them first as child views)
    }
}
