package com.github.ifeel.uvcpad.bt.listeners

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.GestureDetectorCompat
import com.github.ifeel.uvcpad.bt.senders.RelativeMouseSender
import kotlin.math.roundToInt

/**
 * Touch listener that translates finger gestures into HID mouse reports.
 *
 * Architecture modelled after Mousedroid GestureHandler:
 * - GestureDetector.onScroll: uses e1/e2.pointerCount to distinguish single/dual-finger
 *   * single-finger = mouse move with sub-pixel accumulation + speed coefficient
 *   * dual-finger   = HID scroll (unless ScaleGestureDetector is in progress)
 * - onSingleTapUp + activePointerCount: distinguish left-click (1 finger) vs right-click (≥2)
 * - onLongPress: begin drag (left-button held)
 * - Double-tap → enter drag mode; quick double-tap → double-click
 * - ScaleGestureDetector: pinch-to-zoom (future: wire to action)
 * - onTouch: dispatches to both detectors + tracks pointer state
 *
 * Wheel mode (Scheme C): onTouch branches at entry — Wheel mode bypasses GestureDetector
 * and ScaleGestureDetector entirely, handling touch events as pure scroll via handleWheelTouch.
 */
class ViewListener(
    val hidDevice: BluetoothHidDevice,
    val host: BluetoothDevice,
    val rMouseSender: RelativeMouseSender
) : View.OnTouchListener {

    // ==================== Haptics ====================

    private var vibrator: Vibrator? = null

    // ==================== State ====================

    /** Number of pointers currently on screen. Updated in onTouch before detector dispatch. */
    private var activePointerCount = 0

    /** True while a scale (pinch) gesture is in progress — suppresses two-finger scroll. */
    private var scaled = false

    /** True when a two-finger onScroll has been sent — suppresses spurious right-click on lift. */
    private var scrolled = false

    /** True when a right-click has been sent via ACTION_POINTER_UP — suppresses ghost left-click in onSingleTapUp. */
    private var rightClickSent = false

    /** True when long-press has fired and left button is held. */
    private var isLongPressed = false

    /** True when a double-tap drag is active (left button held after double-tap hold). */
    private var isDragging = false

    /** True when double-tap-hold drag is active (moved from GestureListener). */
    private var doubleTapActive = false

    /** True when the toolbar Left button is being held — drag-mode indicator. */
    private var buttonHeld = false

    /** Touch coordinates from the last event, for drag delta calculation in ACTION_MOVE. */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // ==================== Multi-shot wake ====================

    private val wakeHandler = Handler(Looper.getMainLooper())
    private val wakeRunnables = mutableListOf<Runnable>()

    // ==================== Tap detection ====================

    private val tapChecker = Handler(Looper.getMainLooper())
    private var singleTapRunnable: Runnable? = null
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    // ==================== Double-tap drag ====================

    private var doubleTapRunnable: Runnable? = null
    private var dragConfirmed = false
    private val dragStartDelay = 150L

    // ==================== Custom drag trigger (200ms, faster than system 500ms) ====================

    private val dragHandler = Handler(Looper.getMainLooper())
    private var dragRunnable: Runnable? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragSlop = 0  // initialized on first touch from ViewConfiguration

    // ==================== Gesture detectors (lazy-init on first touch) ====================

    private var gestureDetector: GestureDetectorCompat? = null
    private var scaleDetector: ScaleGestureDetector? = null

    // ==================== Speed coefficients (set externally) ====================

    var mouseSpeed: Float = 0.4f
    var scrollSpeed: Float = 0.33f

    /** Called by SelectDeviceActivity when the Left toolbar button is pressed/released. */
    fun setButtonHeld(held: Boolean) {
        buttonHeld = held
    }

    // ==================== Wheel mode ====================

    /** True while the Wheel button is held — single-finger movement becomes scroll. */
    private var wheelMode = false

    /** Called by SelectDeviceActivity when the Wheel toolbar button is pressed/released. */
    fun setWheelMode(enabled: Boolean) {
        wheelMode = enabled
    }

    // ==================== Speed ramp (smooth acceleration on touch-down) ====================

    private var touchDownTime = 0L
    private val RAMP_DURATION_MS = 400L           // ramp duration
    private val RAMP_START_FRACTION = 0.15f        // starting speed fraction (15%)

    /**
     * Calculate current speed ramp coefficient (0.15 → 1.0, linear ramp over RAMP_DURATION_MS).
     * Called every frame after touch-down; coefficient grows linearly with elapsed time.
     */
    private fun getRampMultiplier(skipRamp: Boolean = false): Float {
        if (skipRamp) return 1.0f
        val elapsed = System.currentTimeMillis() - touchDownTime
        if (elapsed >= RAMP_DURATION_MS) return 1.0f
        return RAMP_START_FRACTION + (1.0f - RAMP_START_FRACTION) * (elapsed.toFloat() / RAMP_DURATION_MS)
    }

    // ==================== Sub-pixel accumulators ====================

    private var accumMouseX = 0f
    private var accumMouseY = 0f
    private var accumScrollX = 0f
    private var accumScrollY = 0f

    private fun resetAccumulators() {
        accumMouseX = 0f
        accumMouseY = 0f
        accumScrollX = 0f
        accumScrollY = 0f
    }

    // ==================== onTouch — entry point ====================

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null || v == null) return false

        // Lazy-init detectors
        if (gestureDetector == null) {
            vibrator = v.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            gestureDetector = GestureDetectorCompat(v.context, GestureListener())
        }
        if (scaleDetector == null) {
            scaleDetector = ScaleGestureDetector(v.context, ScaleListener())
        }

        // Wheel mode: pure MotionEvent handling, bypass GestureDetector & ScaleDetector
        if (wheelMode) {
            handleWheelTouch(event)
            return true
        }

        // Normal mode: GestureDetector + ScaleDetector
        handleNormalTouch(v, event)
        return true
    }

    // ==================== Wheel mode touch handling ====================

    /**
     * Pure MotionEvent → scroll translation.
     * Wheel mode bypasses GestureDetector/ScaleDetector entirely.
     * Single-finger movement maps directly to HID scroll.
     */
    private fun handleWheelTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                resetAccumulators()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                accumScrollX += -dx * scrollSpeed  // invert: finger move → content scroll
                accumScrollY += -dy * scrollSpeed
                val sx = accumScrollX.roundToInt()
                val sy = accumScrollY.roundToInt()
                if (sx != 0 || sy != 0) {
                    rMouseSender.sendScroll(sy, sx)
                    accumScrollX -= sx
                    accumScrollY -= sy
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetAccumulators()
            }
        }
    }

    // ==================== Normal mode touch handling ====================

    /**
     * Pointer-state tracking + GestureDetector/ScaleDetector dispatch.
     * Extracted from the original onTouch body.
     */
    private fun handleNormalTouch(v: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount = event.pointerCount
            }
            MotionEvent.ACTION_MOVE -> {
                // Cancel custom drag trigger if finger moved beyond touch slop
                dragRunnable?.let {
                    if (dragSlop == 0) {
                        dragSlop = ViewConfiguration.get(v.context).scaledTouchSlop
                    }
                    val dx = Math.abs(event.x - dragStartX)
                    val dy = Math.abs(event.y - dragStartY)
                    if (dx > dragSlop || dy > dragSlop) {
                        dragHandler.removeCallbacks(it)
                        dragRunnable = null
                    }
                }

                // Only handle long-press drag directly (onScroll is swallowed during long-press).
                // Double-tap drag movement goes through GestureDetector.onScroll to avoid double-send.
                if (isLongPressed) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val ramp = getRampMultiplier(skipRamp = true)
                    accumMouseX += dx * mouseSpeed * ramp
                    accumMouseY += dy * mouseSpeed * ramp
                    var mx = Math.round(accumMouseX)
                    var my = Math.round(accumMouseY)
                    if (mx != 0 || my != 0) {
                        rMouseSender.sendMouseMove(mx, my)
                        accumMouseX -= mx
                        accumMouseY -= my
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Right-click on two-finger lift — but ONLY when the user was NOT
                // pinching (scaled) or scrolling (scrolled).  The gesture detector's
                // onSingleTapUp handles the static two-finger tap; this handles the
                // case where the second finger lifts after a brief touch.
                if (event.pointerCount == 2 && !scaled && !scrolled && !buttonHeld) {
                    val fingerDistance = Math.hypot(
                        (event.getX(0) - event.getX(1)).toDouble(),
                        (event.getY(0) - event.getY(1)).toDouble()
                    ).toFloat()
                    if (fingerDistance > 30f) {
                        Log.i("ViewListener", "Two-finger right click (POINTER_UP)")
                        rMouseSender.sendRightClick()
                        rightClickSent = true
                    }
                }
                activePointerCount = event.pointerCount
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // Cancel all pending runnables to prevent state leaks
                dragRunnable?.let { dragHandler.removeCallbacks(it) }
                dragRunnable = null
                wakeHandler.removeCallbacksAndMessages(null)
                wakeRunnables.clear()
                singleTapRunnable?.let { tapChecker.removeCallbacks(it) }
                doubleTapRunnable?.let { tapChecker.removeCallbacks(it) }

                // Release drag state on final lift — only when no external button held.
                // If buttonHeld is true the toolbar button owns leftButton; releasing here
                // would prematurely drop the drag.
                if (!buttonHeld) {
                    if (isLongPressed || isDragging) {
                        rMouseSender.sendLeftClickOff()
                        Log.d("ViewListener", "Drag released (UP/CANCEL)")
                    }
                }
                isLongPressed = false
                isDragging = false
                activePointerCount = 0
                // Reset gesture-scope flags
                resetAccumulators()
                scaled = false
                scrolled = false
                rightClickSent = false
            }
        }

        // Dispatch to both detectors — Mousedroid pattern
        scaleDetector?.onTouchEvent(event)
        gestureDetector?.onTouchEvent(event)
    }

    // ===================== GestureDetector Listener =====================

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            Log.d("ViewListener", "onDown: sending multi-shot wake")

            // Reset gesture-chain state
            isDragging = false
            isLongPressed = false
            scaled = false
            scrolled = false

            // Record initial touch position for drag delta calculation
            lastTouchX = e.x
            lastTouchY = e.y

            // Schedule custom 500ms drag trigger (faster than system default long-press).
            // Skip when buttonHeld is active to avoid unwanted left-click.
            dragStartX = e.x
            dragStartY = e.y
            dragRunnable?.let { dragHandler.removeCallbacks(it) }
            dragRunnable = null
            if (!buttonHeld) {
                dragRunnable = Runnable {
                    if (!isDragging && !isLongPressed && !doubleTapActive) {
                        isLongPressed = true
                        rMouseSender.sendLeftClickOn()
                        vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                        Log.d("ViewListener", "Custom drag (500ms): left button held, entering drag mode")
                    }
                }
                dragHandler.postDelayed(dragRunnable!!, 500L)
            }

            // Record gesture start time for speed ramp
            touchDownTime = System.currentTimeMillis()

            // Reset sub-pixel accumulators at the start of every gesture chain
            resetAccumulators()

            // Multi-shot wake: 4 zero-reports at 5ms intervals.
            // Skip when buttonHeld — reset() would clear leftButton mid-drag.
            if (!buttonHeld) {
                wakeHandler.removeCallbacksAndMessages(null)
                wakeRunnables.clear()
                for (i in 0 until 4) {
                    val runnable = Runnable {
                        rMouseSender.mouseReport.reset()
                        hidDevice.sendReport(host, 4, rMouseSender.mouseReport.bytes)
                    }
                    wakeRunnables.add(runnable)
                    wakeHandler.postDelayed(runnable, i * 5L)
                }
            }
            // Must return true to claim the event chain
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isLongPressed) {
                // Already triggered by custom 200ms drag — avoid double-send
                return
            }
            dragRunnable?.let { dragHandler.removeCallbacks(it) }
            dragRunnable = null
            isLongPressed = true
            rMouseSender.sendLeftClickOn()
            vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.d("ViewListener", "onLongPress (500ms fallback): left button held, entering drag mode")
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // Cancel pending multi-shot wake runnables once scrolling starts
            for (r in wakeRunnables) {
                wakeHandler.removeCallbacks(r)
            }
            wakeRunnables.clear()

            // Determine pointer count from the scroll events (Mousedroid pattern).
            // e1 may be null on the very first scroll; e2 always holds current state.
            val ptrCount = maxOf(e1?.pointerCount ?: 1, e2.pointerCount)

            when {
                // ---------- Two-finger scroll ----------
                ptrCount >= 2 -> {
                    if (scaleDetector?.isInProgress == true) {
                        // Pinch-to-zoom active — let ScaleListener handle it;
                        // skip scroll to avoid conflicting HID reports.
                        return true
                    }
                    scrolled = true
                    val ramp = getRampMultiplier()
                    accumScrollY += -distanceY * scrollSpeed * ramp
                    accumScrollX += -distanceX * scrollSpeed * ramp
                    val sy = Math.round(accumScrollY)
                    val sx = Math.round(accumScrollX)
                    if (sy != 0 || sx != 0) {
                        rMouseSender.sendScroll(sy, sx)
                        accumScrollY -= sy
                        accumScrollX -= sx
                    }
                }
                // ---------- Single-finger mouse move ----------
                else -> {
                    val ramp = getRampMultiplier(skipRamp = isLongPressed || isDragging || buttonHeld)
                    accumMouseX += -distanceX * mouseSpeed * ramp
                    accumMouseY += -distanceY * mouseSpeed * ramp
                    var mx = Math.round(accumMouseX)
                    var my = Math.round(accumMouseY)
                    if (mx != 0 || my != 0) {
                        if (buttonHeld) {
                            rMouseSender.sendDragMove(mx, my)
                        } else {
                            rMouseSender.sendMouseMove(mx, my)
                        }
                        accumMouseX -= mx
                        accumMouseY -= my
                    }
                }
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Button held — suppress tap to prevent unwanted left/right click.
            if (buttonHeld) return true
            // Skip if right-click already sent by ACTION_POINTER_UP — prevents ghost left-click.
            if (rightClickSent) return true
            // Use activePointerCount (tracked in onTouch) — more reliable than
            // e.pointerCount which may report stale values from the event.
            if (activePointerCount >= 2) {
                Log.i("ViewListener", "Two-finger tap → right click")
                rMouseSender.sendRightClick()
                return true
            }

            // Single-finger tap: schedule left-click via double-tap timeout
            Log.d("ViewListener", "onSingleTapUp: scheduling tap check")
            singleTapRunnable?.let { tapChecker.removeCallbacks(it) }

            singleTapRunnable = Runnable {
                Log.d("ViewListener", "Single tap confirmed → left click")
                rMouseSender.sendTestClick()
            }
            tapChecker.postDelayed(singleTapRunnable!!, doubleTapTimeout)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            Log.d("ViewListener", "onDoubleTap: cancelling single-tap timer")
            singleTapRunnable?.let { tapChecker.removeCallbacks(it) }
            singleTapRunnable = null
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            Log.d("ViewListener", "onDoubleTapEvent action=${e.action}")
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (buttonHeld) return true
                    dragConfirmed = false
                    isDragging = true
                    doubleTapActive = true
                    rMouseSender.sendLeftClickOn()
                    vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                    lastTouchX = e.x
                    lastTouchY = e.y
                    Log.d("ViewListener", "Double-tap drag: left button down")
                }
                MotionEvent.ACTION_MOVE -> {
                    // GestureDetector dispatches ACTION_MOVE here instead of onScroll
                    // during the double-tap sequence — handle movement directly.
                    val dx = e.x - lastTouchX
                    val dy = e.y - lastTouchY
                    accumMouseX += dx * mouseSpeed
                    accumMouseY += dy * mouseSpeed
                    val mx = Math.round(accumMouseX)
                    val my = Math.round(accumMouseY)
                    if (mx != 0 || my != 0) {
                        rMouseSender.sendMouseMove(mx, my)
                        accumMouseX -= mx.toFloat()
                        accumMouseY -= my.toFloat()
                    }
                    lastTouchX = e.x
                    lastTouchY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    // UP is also handled by onTouch ACTION_UP/CANCEL where
                    // isDragging is reset and left button is released.
                    // Clean up any pending drag runnable.
                    doubleTapRunnable?.let { tapChecker.removeCallbacks(it) }
                    doubleTapRunnable = null
                    dragConfirmed = false
                    doubleTapActive = false
                }
            }
            return true
        }
    }

    // ===================== ScaleGestureDetector Listener =====================

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaled = true
            Log.d("ViewListener", "Scale begin: span=${detector.currentSpan}")
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Pinch-to-zoom: send Ctrl+scroll as zoom action.
            val factor = detector.scaleFactor
            val scrollDelta = Math.round((factor - 1.0f) * 10)
            if (scrollDelta != 0) {
                rMouseSender.sendScroll(scrollDelta, 0)
            }
            Log.d("ViewListener", "Scale: factor=$factor scrollDelta=$scrollDelta")
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaled = false
            Log.d("ViewListener", "Scale end")
        }
    }
}
