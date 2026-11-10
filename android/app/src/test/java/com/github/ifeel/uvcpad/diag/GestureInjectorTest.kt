package com.github.ifeel.uvcpad.diag

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.ifeel.uvcpad.touch.TransparentTouchLayer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [uvcpad-t0-inject] Robolectric end-to-end: parse → inject → the real TransparentTouchLayer
 * forwards the constructed MotionEvents to a recording gesture listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GestureInjectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Snapshot(
        val action: Int,
        val actionIndex: Int,
        val pointerCount: Int,
        val ids: List<Int>,
        val xs: List<Float>,
        val ys: List<Float>,
        val downTime: Long,
        val eventTime: Long
    )

    private class RecordingListener : View.OnTouchListener {
        val events = mutableListOf<Snapshot>()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            events += Snapshot(
                event.actionMasked,
                event.actionIndex,
                event.pointerCount,
                (0 until event.pointerCount).map { event.getPointerId(it) },
                (0 until event.pointerCount).map { event.getX(it) },
                (0 until event.pointerCount).map { event.getY(it) },
                event.downTime,
                event.eventTime
            )
            return true
        }
    }

    private fun newLayer(): TransparentTouchLayer {
        val layer = TransparentTouchLayer(context)
        layer.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        layer.layout(0, 0, 1000, 1000)
        return layer
    }

    private fun steps(input: String): List<GestureStep> =
        (GestureSequenceParser.parse(input) as ParseResult.Success).steps

    @Test
    fun singleFingerSequenceReachesTouchLayerWithCoordinates() {
        val layer = newLayer()
        val recorder = RecordingListener()
        layer.setGestureListener(recorder)

        val delivered = GestureInjector.inject(
            layer, steps("down:10,20;move:30,40;up:30,40"), startTime = 1_000L
        )

        assertEquals(3, delivered)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            recorder.events.map { it.action }
        )
        assertEquals(listOf(10f, 30f, 30f), recorder.events.map { it.xs.single() })
        assertEquals(listOf(20f, 40f, 40f), recorder.events.map { it.ys.single() })
        assertEquals(listOf(1, 1, 1), recorder.events.map { it.pointerCount })
        assertEquals(listOf(1_000L, 1_001L, 1_002L), recorder.events.map { it.eventTime })
        assertEquals(listOf(1_000L, 1_000L, 1_000L), recorder.events.map { it.downTime })
    }

    @Test
    fun secondFingerUsesPointerDownAndPointerUp() {
        val layer = newLayer()
        val recorder = RecordingListener()
        layer.setGestureListener(recorder)

        val delivered = GestureInjector.inject(
            layer,
            steps("down:10,10;down2:50,50;move2:60,60;up2:60,60;up:10,10"),
            startTime = 2_000L
        )

        assertEquals(5, delivered)
        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP
            ),
            recorder.events.map { it.action }
        )
        assertEquals(listOf(1, 2, 2, 2, 1), recorder.events.map { it.pointerCount })
        assertEquals(listOf(1, 0, 1), recorder.events.slice(1..3).map { it.actionIndex })
        assertEquals(
            listOf(listOf(0, 1), listOf(0, 1), listOf(0, 1), listOf(0)),
            recorder.events.slice(1..4).map { it.ids }
        )
        // The second MOVE/POINTER_UP events carry both pointers: [10,60] / [10,60].
        assertEquals(listOf(listOf(10f, 60f), listOf(10f, 60f)), recorder.events.slice(2..3).map { it.xs })
    }

    @Test
    fun sleepOnlyAdvancesSyntheticEventTime() {
        val layer = newLayer()
        val recorder = RecordingListener()
        layer.setGestureListener(recorder)

        GestureInjector.inject(layer, steps("down:1,1;sleep:100;up:1,1"), startTime = 500L)

        assertEquals(2, recorder.events.size)
        assertEquals(500L, recorder.events[0].eventTime)
        // down at 500 (+1 ms step) → sleep adds 100 (+1 ms step) → up at 602.
        assertEquals(602L, recorder.events[1].eventTime)
    }
}
