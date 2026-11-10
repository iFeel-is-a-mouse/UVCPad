package com.github.ifeel.uvcpad.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/** [uvcpad-t0-hud] Sliding-window maths pinned by hand-computed values. */
class RateWindowTest {

    @Test
    fun emptyWindowReportsZero() {
        val window = RateWindow(1_000L)
        assertEquals(0, window.rate(5_000L))
    }

    @Test
    fun samplesInsideWindowAreCounted() {
        val window = RateWindow(1_000L)
        window.mark(1_000L)
        window.mark(1_500L)

        assertEquals(2, window.rate(1_999L)) // 1000 and 1500 are inside (1999-1000 = 999)
        window.mark(2_000L)
        assertEquals(3, window.rate(2_000L)) // boundary sample stays (age == window)
    }

    @Test
    fun samplesOlderThanWindowAreEvicted() {
        val window = RateWindow(1_000L)
        window.mark(1_000L)
        window.mark(1_500L)

        assertEquals(1, window.rate(2_001L)) // 2_001-1_000 = 1001 > 1000 → evicted
        assertEquals(0, window.rate(2_501L))
    }

    @Test
    fun resetClearsWindow() {
        val window = RateWindow(1_000L)
        window.mark(10L)
        window.reset()
        assertEquals(0, window.rate(10L))
    }
}
