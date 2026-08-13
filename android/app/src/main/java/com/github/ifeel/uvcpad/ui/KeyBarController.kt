package com.github.ifeel.uvcpad.ui

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Key bar show/hide control (new in DESIGN §3.4; show/hide and race prevention adapted from hdmi2mp showToolbar/hideToolbar/hideGeneration):
 * - [show]: slide in + reset the auto-hide timer (hideGeneration++ invalidates any in-flight hide animation);
 * - [hide]: slide back + set GONE when the animation ends (generation check: stays visible if show interrupts during the animation);
 * - [resetAutoHideTimer]: called on any touch (triangle/key bar/touch layer), resets the [autoHideMs] timer;
 * - [destroy]: clears the timer and animations on onDestroy to avoid Handler leaks.
 *
 * Initial state: key bar GONE (visibility="gone" in the layout), only the triangle is always visible; the first show slides in from above the top edge.
 */
class KeyBarController(
    private val panel: View,
    private val autoHideMs: Long
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var hideGeneration = 0
    private var visible = false

    val isVisible: Boolean get() = visible

    /** Expands the key bar and resets the auto-hide timer; when already expanded it only resets the timer (idempotent, hdmi2mp pattern) */
    fun show() {
        hideGeneration++
        mainHandler.removeCallbacks(hideRunnable)
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            // First expansion: slide in from above the top edge (translationY is already -height after hide(), no need to set it again)
            if (panel.translationY == 0f) {
                panel.translationY = -panel.height.toFloat()
            }
        }
        panel.animate().cancel()
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(FADE_IN_MS)
            .start()
        visible = true
        resetAutoHideTimer()
    }

    /** Collapses the key bar: slides back above the top edge, sets GONE when the animation ends (stays visible if show interrupts during the animation) */
    fun hide() {
        if (!visible || panel.visibility != View.VISIBLE) return
        visible = false
        mainHandler.removeCallbacks(hideRunnable)
        val gen = hideGeneration
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .translationY(-panel.height.toFloat())
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                // Race protection: user expands again during the animation (generation changed) → keep visible
                if (hideGeneration == gen && panel.visibility == View.VISIBLE) {
                    panel.visibility = View.GONE
                }
            }
            .start()
    }

    /** Triangle tap entry: collapsed→expand; expanded→collapse (DESIGN §3.3) */
    fun toggle() {
        if (visible) hide() else show()
    }

    /** Any touch resets the auto-hide timer (DESIGN §3.4: "any touch resets the timer", consistent with hdmi2mp) */
    fun resetAutoHideTimer() {
        if (!visible) return
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, autoHideMs)
    }

    /** Called from Activity onDestroy: clears the timer and animations (hdmi2mp: removeCallbacksAndMessages) */
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        panel.animate().cancel()
    }

    companion object {
        private const val FADE_IN_MS = 150L
        private const val FADE_OUT_MS = 200L
    }
}
