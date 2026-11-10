package com.github.ifeel.uvcpad.diag

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.github.ifeel.uvcpad.BuildConfig

/**
 * [uvcpad-t0] Structured, channel-scoped diagnostics (debug builds only).
 *
 * Every entry point is a no-op in release builds: [BuildConfig.DEBUG] is folded at build
 * time, and the field is checked again at every call site so no diagnostic string is
 * formatted or logged in release.
 *
 * Channels: UVCPAD_CAM / UVCPAD_BT / UVCPAD_HID / UVCPAD_TOUCH / UVCPAD_UI.
 * Format is uniform `k=v k=v`, e.g. `Diag.cam("state=OPENED w=1600 h=1200 mode=4:3 rot=90")`.
 *
 * Rate counters:
 * - [onFrame] is fed by the AUSBC NV21 preview callback (1s sliding window → HUD fps)
 * - [onHidReportSent] is fed by RelativeMouseSender right before sendReport
 * - [startPeriodic] emits one HID rate summary per second (never per report)
 * - [hidReport] emits the per-report hex line ONLY while the HUD is enabled
 */
object Diag {

    const val CAM = "UVCPAD_CAM"
    const val BT = "UVCPAD_BT"
    const val HID = "UVCPAD_HID"
    const val TOUCH = "UVCPAD_TOUCH"
    const val UI = "UVCPAD_UI"

    private const val RATE_WINDOW_MS = 1_000L
    private const val SUMMARY_INTERVAL_MS = 1_000L

    /** True while the debug HUD is on screen; gates high-frequency per-report lines. */
    @Volatile
    var hudEnabled: Boolean = false

    private val fpsWindow = RateWindow(RATE_WINDOW_MS)
    private val hidWindow = RateWindow(RATE_WINDOW_MS)
    private val startedAt = SystemClock.elapsedRealtime()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var summaryRunnable: Runnable? = null

    // ==================== Channels ====================

    fun cam(message: String) = log(CAM, message)

    fun bt(message: String) = log(BT, message)

    fun hid(message: String) = log(HID, message)

    fun touch(message: String) = log(TOUCH, message)

    fun ui(message: String) = log(UI, message)

    private fun log(channel: String, message: String) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.i(channel, message)
        } catch (_: Throwable) {
            // Diagnostics must never break the capture/touch path.
        }
    }

    // ==================== Rates ====================

    /** One decoded preview frame (called from the AUSBC capture thread). */
    fun onFrame(now: Long = SystemClock.elapsedRealtime()) {
        if (!BuildConfig.DEBUG) return
        fpsWindow.mark(now)
    }

    /** Current fps over the trailing 1s window. */
    fun fps(now: Long = SystemClock.elapsedRealtime()): Int {
        if (!BuildConfig.DEBUG) return 0
        return fpsWindow.rate(now)
    }

    /** One sendReport call (counted before the stack call, as specified: reports/s). */
    fun onHidReportSent(now: Long = SystemClock.elapsedRealtime()) {
        if (!BuildConfig.DEBUG) return
        hidWindow.mark(now)
    }

    /** Current HID reports/s over the trailing 1s window. */
    fun hidRate(now: Long = SystemClock.elapsedRealtime()): Int {
        if (!BuildConfig.DEBUG) return 0
        return hidWindow.rate(now)
    }

    // ==================== Per-report line (HUD only) ====================

    /**
     * Emits the full HID report only while the HUD is visible; the per-second summary is the
     * normal-path log so logcat is not flooded by 60+ lines/s.
     */
    fun hidReport(
        reportId: Int,
        bytes: ByteArray,
        dx: Int,
        dy: Int,
        buttons: Int,
        scroll: Int
    ) {
        if (!BuildConfig.DEBUG || !hudEnabled) return
        val t = SystemClock.elapsedRealtime() - startedAt
        log(
            HID,
            "hid t=$t id=$reportId dx=$dx dy=$dy btn=$buttons scroll=$scroll hex=${toHex(bytes)}"
        )
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    // ==================== 1s summary ====================

    /** Starts the once-per-second HID rate summary. Idempotent; call from the Activity on create. */
    fun startPeriodic() {
        if (!BuildConfig.DEBUG || summaryRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                val rate = hidRate()
                log(HID, "rate=$rate/s")
                mainHandler.postDelayed(this, SUMMARY_INTERVAL_MS)
            }
        }
        summaryRunnable = runnable
        mainHandler.postDelayed(runnable, SUMMARY_INTERVAL_MS)
    }

    /** Stops the summary and clears both windows; call from onDestroy. */
    fun stopPeriodic() {
        summaryRunnable?.let { mainHandler.removeCallbacks(it) }
        summaryRunnable = null
        fpsWindow.reset()
        hidWindow.reset()
        hudEnabled = false
    }
}
