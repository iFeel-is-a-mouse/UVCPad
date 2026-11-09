package com.github.ifeel.uvcpad.touch

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Simulated input (touch chain) for the rotation button feature.
 *
 * Dispatches real MotionEvent sequences through [TransparentTouchLayer.dispatchTouchEvent] and records the
 * coordinates that actually reach the gesture layer (a fake [View.OnTouchListener]), i.e. the contract the
 * gesture engine consumes — not the private rotation matrix.
 *
 * The expected table below is derived BY HAND from the documented fix (commit 97cf3eb, [uvcpad-rotate-p1]):
 * the preview is rotated CLOCKWISE by the AUSBC MVP, so touch coordinates must be rotated back
 * COUNTER-clockwise; with Android screen coordinates (y down) that inverse is `Matrix.setRotate(-degrees)`:
 *
 *   θ=0   : (dx, dy) → ( dx,  dy)
 *   θ=90  : (dx, dy) → ( dy, -dx)      displayed right = original up
 *   θ=180 : (dx, dy) → (-dx, -dy)
 *   θ=270 : (dx, dy) → (-dy,  dx)
 *
 * The literals in each test method are those hand-derived values; nothing is computed from the implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransparentTouchLayerRotationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ==================== helpers ====================

    private fun newLayer(width: Int, height: Int): TransparentTouchLayer {
        val layer = TransparentTouchLayer(context)
        layer.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        layer.layout(0, 0, width, height)
        return layer
    }

    /** Records the coordinates of every dispatched event, per pointer. */
    private class RecordingTouchListener : View.OnTouchListener {
        data class Snapshot(val action: Int, val points: List<Pair<Float, Float>>)

        val events = mutableListOf<Snapshot>()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            events += Snapshot(
                event.actionMasked,
                (0 until event.pointerCount).map { event.getX(it) to event.getY(it) }
            )
            return true
        }

        fun lastPoint(): Pair<Float, Float> = events.last().points.single()
    }

    private fun singleEvent(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long = 0L
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    private fun multiEvent(
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        eventTime: Long,
        downTime: Long = 0L
    ): MotionEvent {
        val properties = Array(xs.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(xs.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                y = ys[index]
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            xs.size,
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
    }

    /**
     * Dispatches DOWN at the view centre followed by MOVE at centre + (dx, dy) and returns the delta the
     * gesture layer observed (MOVE point − DOWN point), which must equal the rotated delta of exactly (dx, dy).
     */
    private fun observedDelta(degrees: Int, dx: Float, dy: Float, width: Int = 1600, height: Int = 1200): Pair<Float, Float> {
        val layer = newLayer(width, height)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = degrees

        val centerX = width / 2f
        val centerY = height / 2f

        val down = singleEvent(MotionEvent.ACTION_DOWN, centerX, centerY, 0L)
        val move = singleEvent(MotionEvent.ACTION_MOVE, centerX + dx, centerY + dy, 10L)
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(move)
            // The layer must hand a COPY to the gesture engine: the original event stays untouched
            assertEquals("original MotionEvent must not be mutated", centerX + dx, move.x, 0.001f)
        } finally {
            down.recycle()
            move.recycle()
        }

        assertEquals("gesture layer must see DOWN + MOVE", 2, recorder.events.size)
        val downPoint = recorder.events[0].points.single()
        val movePoint = recorder.events[1].points.single()
        // DOWN lands exactly on the rotation centre → invariant under the rotation
        assertEquals(centerX, downPoint.first, 0.01f)
        assertEquals(centerY, downPoint.second, 0.01f)
        return (movePoint.first - downPoint.first) to (movePoint.second - downPoint.second)
    }

    private data class Increment(val name: String, val dx: Float, val dy: Float)

    private val increments = listOf(
        Increment("pure +x (finger right)", 100f, 0f),
        Increment("pure +y (finger down)", 0f, 100f),
        Increment("diagonal (+x,+y)", 100f, 100f),
        Increment("negative (-x,-y)", -100f, -100f)
    )

    private fun assertTable(degrees: Int, expected: List<Pair<Float, Float>>) {
        assertEquals("table must cover every increment", increments.size, expected.size)
        increments.forEachIndexed { index, increment ->
            val (actualDx, actualDy) = observedDelta(degrees, increment.dx, increment.dy)
            val (expectedDx, expectedDy) = expected[index]
            assertEquals(
                "θ=$degrees ${increment.name}: dx",
                expectedDx,
                actualDx,
                0.01f
            )
            assertEquals(
                "θ=$degrees ${increment.name}: dy",
                expectedDy,
                actualDy,
                0.01f
            )
        }
    }

    // ==================== single-finger table: 4 angles × 4 increments ====================

    @Test
    fun rotation0_passesGestureDeltasThroughUnchanged() {
        assertTable(
            0,
            listOf(
                100f to 0f,
                0f to 100f,
                100f to 100f,
                -100f to -100f
            )
        )
    }

    @Test
    fun rotation90_appliesCounterClockwiseInverse() {
        // (dx,dy) → (dy,-dx): finger right → gesture up, finger down → gesture right
        assertTable(
            90,
            listOf(
                0f to -100f,
                100f to 0f,
                100f to -100f,
                -100f to 100f
            )
        )
    }

    @Test
    fun rotation180_negatesGestureDeltas() {
        assertTable(
            180,
            listOf(
                -100f to 0f,
                0f to -100f,
                -100f to -100f,
                100f to 100f
            )
        )
    }

    @Test
    fun rotation270_appliesClockwiseInverse() {
        // (dx,dy) → (-dy,dx): finger right → gesture down, finger down → gesture left
        assertTable(
            270,
            listOf(
                0f to 100f,
                -100f to 0f,
                -100f to 100f,
                100f to -100f
            )
        )
    }

    // ==================== finger swipe DOWN: the fixed-direction conclusion ====================

    @Test
    fun fingerSwipeDown_mapsPerRotationTable() {
        // Hand-derived (dy = +100): 0° → down, 90° → right, 180° → up, 270° → left.
        // These are the directions the gesture engine (and therefore the PC cursor) must receive after 97cf3eb.
        val expected = mapOf(
            0 to (0f to 100f),
            90 to (100f to 0f),
            180 to (0f to -100f),
            270 to (-100f to 0f)
        )
        expected.forEach { (degrees, expectedDelta) ->
            val (actualDx, actualDy) = observedDelta(degrees, 0f, 100f)
            assertEquals("θ=$degrees swipe-down dx", expectedDelta.first, actualDx, 0.01f)
            assertEquals("θ=$degrees swipe-down dy", expectedDelta.second, actualDy, 0.01f)
        }
    }

    // ==================== multi-pointer ====================

    @Test
    fun multiPointer_pointerDownAndMove_transformsEveryPointerAt90() {
        val layer = newLayer(1600, 1200)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = 90

        val down = singleEvent(MotionEvent.ACTION_DOWN, 800f, 600f, 0L)
        val pointerDown = multiEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            floatArrayOf(800f, 1000f),
            floatArrayOf(600f, 800f),
            10L
        )
        val move = multiEvent(
            MotionEvent.ACTION_MOVE,
            floatArrayOf(850f, 1050f),
            floatArrayOf(650f, 850f),
            20L
        )
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(pointerDown)
            layer.dispatchTouchEvent(move)
        } finally {
            down.recycle()
            pointerDown.recycle()
            move.recycle()
        }

        assertEquals(MotionEvent.ACTION_POINTER_DOWN, recorder.events[1].action)
        // Both pointers ride the same rotation: centre (800,600), θ=90 → (dx,dy) → (dy,-dx)
        // pointer 0: (1000,800) delta (200,200) → (200,-200) → (1000,400)
        assertEquals(800f, recorder.events[1].points[0].first, 0.01f)
        assertEquals(600f, recorder.events[1].points[0].second, 0.01f)
        assertEquals(1000f, recorder.events[1].points[1].first, 0.01f)
        assertEquals(400f, recorder.events[1].points[1].second, 0.01f)

        // pointer 0: (850,650) delta (50,50) → (50,-50) → (850,550)
        // pointer 1: (1050,850) delta (250,250) → (250,-250) → (1050,350)
        assertEquals(850f, recorder.events[2].points[0].first, 0.01f)
        assertEquals(550f, recorder.events[2].points[0].second, 0.01f)
        assertEquals(1050f, recorder.events[2].points[1].first, 0.01f)
        assertEquals(350f, recorder.events[2].points[1].second, 0.01f)
    }

    @Test
    fun multiPointer_pointerUp_transformsEveryPointerAt270() {
        val layer = newLayer(1600, 1200)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = 270

        val down = singleEvent(MotionEvent.ACTION_DOWN, 800f, 600f, 0L)
        val pointerDown = multiEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            floatArrayOf(800f, 1000f),
            floatArrayOf(600f, 800f),
            10L
        )
        // Pointer 1 lifts; the event still carries both pointers, both must be transformed
        val pointerUp = multiEvent(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            floatArrayOf(900f, 1000f),
            floatArrayOf(700f, 800f),
            20L
        )
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(pointerDown)
            layer.dispatchTouchEvent(pointerUp)
        } finally {
            down.recycle()
            pointerDown.recycle()
            pointerUp.recycle()
        }

        assertEquals(MotionEvent.ACTION_POINTER_UP, recorder.events[2].action)
        // θ=270 → (dx,dy) → (-dy,dx)
        // pointer 0: (900,700) delta (100,100) → (-100,100) → (700,700)
        // pointer 1: (1000,800) delta (200,200) → (-200,200) → (600,800)
        assertEquals(700f, recorder.events[2].points[0].first, 0.01f)
        assertEquals(700f, recorder.events[2].points[0].second, 0.01f)
        assertEquals(600f, recorder.events[2].points[1].first, 0.01f)
        assertEquals(800f, recorder.events[2].points[1].second, 0.01f)
    }

    // ==================== non-square view: rotation centre must be (w/2, h/2) ====================

    @Test
    fun nonSquareView1600x1200_rotatesAroundViewCentreAt90() {
        val layer = newLayer(1600, 1200)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = 90

        val down = singleEvent(MotionEvent.ACTION_DOWN, 800f, 600f, 0L)
        val move = singleEvent(MotionEvent.ACTION_MOVE, 1600f, 1200f, 10L)
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        // delta (800,600) → (600,-800) around the true centre (800,600) → (1400,-200).
        // A centre of (600,600) (min/2) or a square-view assumption would land elsewhere.
        val point = recorder.lastPoint()
        assertEquals(1400f, point.first, 0.01f)
        assertEquals(-200f, point.second, 0.01f)
    }

    @Test
    fun nonSquareView1600x1200_rotatesAroundViewCentreAt270() {
        val layer = newLayer(1600, 1200)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = 270

        val down = singleEvent(MotionEvent.ACTION_DOWN, 800f, 600f, 0L)
        val move = singleEvent(MotionEvent.ACTION_MOVE, 1600f, 1200f, 10L)
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        // delta (800,600) → (-600,800) → (200,1400)
        val point = recorder.lastPoint()
        assertEquals(200f, point.first, 0.01f)
        assertEquals(1400f, point.second, 0.01f)
    }

    // ==================== portrait non-square view: 1200×1600 parent ====================

    @Test
    fun portraitView1200x1600_rotatesAroundViewCentreAt90() {
        val layer = newLayer(1200, 1600)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = 90

        val down = singleEvent(MotionEvent.ACTION_DOWN, 600f, 800f, 0L)
        val move = singleEvent(MotionEvent.ACTION_MOVE, 1200f, 1600f, 10L)
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        // delta (600,800) → (dy,-dx) = (800,-600) around the true centre (600,800) → (1400,200).
        // A square-view assumption would use min/2 = 600 for both axes and land elsewhere.
        val point = recorder.lastPoint()
        assertEquals(1400f, point.first, 0.01f)
        assertEquals(200f, point.second, 0.01f)
    }

    @Test
    fun portraitView1200x1600_rotatesAroundViewCentreAt270() {
        val layer = newLayer(1200, 1600)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        layer.displayRotationDegrees = 270

        val down = singleEvent(MotionEvent.ACTION_DOWN, 600f, 800f, 0L)
        val move = singleEvent(MotionEvent.ACTION_MOVE, 1200f, 1600f, 10L)
        try {
            layer.dispatchTouchEvent(down)
            layer.dispatchTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        // delta (600,800) → (-dy,dx) = (-800,600) around the true centre (600,800) → (-200,1400).
        val point = recorder.lastPoint()
        assertEquals(-200f, point.first, 0.01f)
        assertEquals(1400f, point.second, 0.01f)
    }

    // ==================== angle normalisation ====================

    @Test
    fun unnormalisedDegrees_areNormalisedBeforeTransform() {
        // -90 ≡ 270 → (100,0) becomes (0,100)
        val negative = observedDelta(-90, 100f, 0f)
        assertEquals(0f, negative.first, 0.01f)
        assertEquals(100f, negative.second, 0.01f)

        // 450 ≡ 90 → (100,0) becomes (0,-100)
        val over = observedDelta(450, 100f, 0f)
        assertEquals(0f, over.first, 0.01f)
        assertEquals(-100f, over.second, 0.01f)
    }

    // ==================== no listener: event chain passes through ====================

    @Test
    fun noGestureListener_consumesNothing() {
        val layer = newLayer(1600, 1200)
        layer.displayRotationDegrees = 90
        val down = singleEvent(MotionEvent.ACTION_DOWN, 800f, 600f, 0L)
        try {
            assertEquals(false, layer.dispatchTouchEvent(down))
        } finally {
            down.recycle()
        }
    }

    @Test
    fun gestureListener_isMountedAndReceivesTheEventChain() {
        val layer = newLayer(1600, 1200)
        val recorder = RecordingTouchListener()
        layer.setGestureListener(recorder)
        val down = singleEvent(MotionEvent.ACTION_DOWN, 800f, 600f, 0L)
        try {
            assertEquals(true, layer.dispatchTouchEvent(down))
        } finally {
            down.recycle()
        }
        assertEquals(1, recorder.events.size)
    }
}
