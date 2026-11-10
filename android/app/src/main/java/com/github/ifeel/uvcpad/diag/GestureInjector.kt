package com.github.ifeel.uvcpad.diag

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * [uvcpad-t0-inject] Replays a parsed sequence as real MotionEvents into the touch layer.
 *
 * Events are built with MotionEvent.obtain (stable pointer ids 0/1, explicit
 * ACTION_POINTER_DOWN/UP index, one shared downTime) and delivered straight to
 * [View.onTouchEvent], so the app's real gesture engine consumes them exactly like a finger.
 *
 * Timing is synthetic: each step advances eventTime by 1 ms and `sleep:ms` adds ms without
 * blocking the caller (the dispatcher runs on the UI thread).
 */
object GestureInjector {

    /**
     * Delivers [steps] to [target]; returns the number of MotionEvents delivered.
     * Invalid sequences are expected to be rejected by [GestureSequenceParser] first.
     */
    fun inject(
        target: View,
        steps: List<GestureStep>,
        startTime: Long = SystemClock.uptimeMillis()
    ): Int {
        val pointers = LinkedHashMap<Int, FloatArray>()
        var eventTime = startTime
        var downTime = startTime
        var downStarted = false
        var delivered = 0

        fun deliver(action: Int) {
            if (pointers.isEmpty()) return
            val ids = pointers.keys.toList()
            val properties = Array(ids.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = ids[i]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(ids.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = pointers.getValue(ids[i])[0]
                    y = pointers.getValue(ids[i])[1]
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                ids.size,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            try {
                target.onTouchEvent(event)
                delivered++
            } finally {
                event.recycle()
            }
        }

        for (step in steps) {
            when (step) {
                is GestureStep.Sleep -> eventTime += step.ms
                is GestureStep.Down -> {
                    if (!downStarted) {
                        downStarted = true
                        downTime = eventTime
                    }
                    pointers[step.pointer] = floatArrayOf(step.x, step.y)
                    if (step.pointer == 0) {
                        deliver(MotionEvent.ACTION_DOWN)
                    } else {
                        val index = pointers.keys.indexOf(step.pointer)
                        deliver(
                            MotionEvent.ACTION_POINTER_DOWN or
                                (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        )
                    }
                }
                is GestureStep.Move -> {
                    if (pointers.containsKey(step.pointer)) {
                        pointers[step.pointer] = floatArrayOf(step.x, step.y)
                        deliver(MotionEvent.ACTION_MOVE)
                    }
                }
                is GestureStep.Up -> {
                    if (pointers.containsKey(step.pointer)) {
                        pointers[step.pointer] = floatArrayOf(step.x, step.y)
                        val index = pointers.keys.indexOf(step.pointer)
                        val action = if (pointers.size > 1) {
                            MotionEvent.ACTION_POINTER_UP or
                                (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        } else {
                            MotionEvent.ACTION_UP
                        }
                        deliver(action)
                        pointers.remove(step.pointer)
                    }
                }
                is GestureStep.Cancel -> {
                    deliver(MotionEvent.ACTION_CANCEL)
                    pointers.clear()
                }
            }
            eventTime += 1L
        }
        Diag.touch("inject delivered=$delivered steps=${steps.size}")
        return delivered
    }
}
