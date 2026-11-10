package com.github.ifeel.uvcpad.diag

import java.util.ArrayDeque

/**
 * [uvcpad-t0] Sliding-window event counter used for the HUD's fps / HID-report rates.
 *
 * A sample marks "now"; the rate is the number of samples still inside the trailing
 * [windowMs] interval. Pure Kotlin (no Android types) so the window maths can be pinned
 * by JVM tests without a device.
 */
class RateWindow(private val windowMs: Long = 1_000L) {

    private val stamps = ArrayDeque<Long>()

    /** Records one sample at [now] (monotonic milliseconds). */
    @Synchronized
    fun mark(now: Long) {
        evict(now)
        stamps.addLast(now)
    }

    /** Number of samples inside the trailing window ending at [now]. */
    @Synchronized
    fun rate(now: Long): Int {
        evict(now)
        return stamps.size
    }

    @Synchronized
    fun reset() {
        stamps.clear()
    }

    private fun evict(now: Long) {
        // Keep the sample exactly on the boundary (age == windowMs) in the window.
        while (stamps.isNotEmpty() && now - stamps.first() > windowMs) {
            stamps.removeFirst()
        }
    }
}
