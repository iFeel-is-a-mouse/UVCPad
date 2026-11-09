package com.github.ifeel.uvcpad.bt.listeners

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.ifeel.uvcpad.testutil.RecordingSender
import com.github.ifeel.uvcpad.touch.TransparentTouchLayer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * End-to-end simulated input → simulated output:
 * MotionEvent → [TransparentTouchLayer] (rotation inverse) → [ViewListener] gesture engine → 7-byte HID report.
 *
 * [android.bluetooth.BluetoothHidDevice] is mocked (Mockito 5 inline mock maker, the class is final) to capture
 * the wake-burst reports that ViewListener writes to the device directly; gesture reports are captured through
 * [RecordingSender] at the sender's emission choke point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ViewListenerEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var layer: TransparentTouchLayer
    private lateinit var hidDevice: android.bluetooth.BluetoothHidDevice
    private lateinit var host: android.bluetooth.BluetoothDevice
    private lateinit var sender: RecordingSender
    private lateinit var listener: ViewListener

    /** Reports the gesture engine sent straight to BluetoothHidDevice (the 4-report wake burst) */
    private val rawReports = mutableListOf<ByteArray>()

    @Before
    fun setUp() {
        layer = TransparentTouchLayer(context)
        layer.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        )
        layer.layout(0, 0, 1600, 1200)

        hidDevice = mock(android.bluetooth.BluetoothHidDevice::class.java)
        host = mock(android.bluetooth.BluetoothDevice::class.java)
        `when`(hidDevice.sendReport(eq(host), eq(4), any(ByteArray::class.java)))
            .thenAnswer { invocation ->
                rawReports += (invocation.arguments[2] as ByteArray).copyOf()
                true
            }

        sender = RecordingSender(hidDevice, host)
        listener = ViewListener(hidDevice, host, sender).also {
            it.mouseSpeed = 1.0f
            it.scrollSpeed = 1.0f
        }
        layer.setGestureListener(listener)
    }

    // ==================== helpers ====================

    private fun dispatchThroughLayer(action: Int, x: Float, y: Float, eventTime: Long) {
        val event = MotionEvent.obtain(0L, eventTime, action, x, y, 0)
        try {
            layer.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun dispatchDown(x: Float = 800f, y: Float = 600f, eventTime: Long = 0L) {
        dispatchThroughLayer(MotionEvent.ACTION_DOWN, x, y, eventTime)
    }

    private fun dispatchMove(x: Float, y: Float, eventTime: Long) {
        dispatchThroughLayer(MotionEvent.ACTION_MOVE, x, y, eventTime)
    }

    private fun dispatchUp(x: Float = 800f, y: Float = 600f, eventTime: Long = 100L) {
        dispatchThroughLayer(MotionEvent.ACTION_UP, x, y, eventTime)
    }

    /**
     * Makes ViewListener's speed ramp deterministic: it is `System.currentTimeMillis()` based (not Robolectric's
     * virtual clock), so we backdate the touch-down timestamp by 5s → ramp = 1.0 for the whole gesture.
     */
    private fun forceRampComplete() {
        val field = ViewListener::class.java.getDeclaredField("touchDownTime")
        field.isAccessible = true
        field.setLong(listener, System.currentTimeMillis() - 5_000L)
    }

    private fun idle(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    // ==================== tap: DOWN/UP → left click reports ====================

    @Test
    fun tap_sendsFourZeroWakeReportsThenClickDownAndUp() {
        dispatchDown()
        // Flush the wake burst (0/5/10/15ms) before the finger lifts: ACTION_UP cancels pending wake reports
        idle(20)
        dispatchUp(eventTime = 100L)

        // Tap confirmation is scheduled after GestureDetector's double-tap timeout (~300ms)
        idle(500)

        // 1) Wake burst: 4 zero reports at 5ms intervals (no movement, no buttons)
        assertEquals(4, rawReports.size)
        rawReports.forEach { assertArrayEquals(ByteArray(7), it) }

        // 2) Tap: button press then release, both 7-byte reports with ID 4
        assertEquals(2, sender.sends.size)
        assertEquals(4, sender.sends[0].reportId)
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0), sender.sends[0].bytes)
        assertArrayEquals(ByteArray(7), sender.sends[1].bytes)
    }

    // ==================== move: swipe → dx/dy report ====================

    @Test
    fun move_sendsDeltaReportWithLittleEndianLayout() {
        dispatchDown()
        forceRampComplete()
        idle(20) // flush the wake burst
        sender.sends.clear()

        dispatchMove(800f, 700f, eventTime = 30L)

        assertEquals("one gesture report for one MOVE", 1, sender.sends.size)
        val bytes = sender.sends[0].bytes
        assertEquals(4, sender.sends[0].reportId)
        assertEquals(7, bytes.size)
        // Finger swiped DOWN 100px at 0°: distanceY = -100 → dy = +100 = 0x0064 (LSB first)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(100, bytes[3].toInt() and 0xFF)
        assertEquals(0x00, bytes[4].toInt() and 0xFF)
        assertEquals(0x00, bytes[5].toInt() and 0xFF)
        assertEquals(0x00, bytes[6].toInt() and 0xFF)
    }

    // ==================== rotation × gesture: the two halves must agree ====================

    @Test
    fun rotation90_swipeDown_reportsHorizontalRightMovement() {
        layer.displayRotationDegrees = 90

        dispatchDown()
        forceRampComplete()
        idle(20)
        sender.sends.clear()

        // Screen-space swipe DOWN 100px. The touch layer maps it to +x in the unrotated frame
        // (see TransparentTouchLayerRotationTest), so the PC must move RIGHT 100px.
        dispatchMove(800f, 700f, eventTime = 30L)

        assertEquals(1, sender.sends.size)
        val bytes = sender.sends[0].bytes
        assertEquals(100, bytes[1].toInt() and 0xFF) // dx = +100
        assertEquals(0x00, bytes[2].toInt())
        assertEquals(0x00, bytes[3].toInt())         // dy = 0
        assertEquals(0x00, bytes[4].toInt())
    }

    // ==================== sub-pixel accumulation ====================

    @Test
    fun longPressDrag_accumulatesSubPixelMovesIntoOnePixel() {
        dispatchDown()
        idle(600) // custom 500ms drag trigger fires → left button held
        sender.sends.clear()

        // 0.2px per MOVE: 0.2 → 0, 0.4 → 0, 0.6 → 1px
        dispatchMove(800.2f, 600f, eventTime = 610L)
        dispatchMove(800.4f, 600f, eventTime = 620L)
        assertTrue("sub-pixel movement must not emit a report yet", sender.sends.isEmpty())

        dispatchMove(800.6f, 600f, eventTime = 630L)

        assertEquals(1, sender.sends.size)
        val bytes = sender.sends[0].bytes
        assertEquals(1, bytes[1].toInt())   // dx LSB = 1
        assertEquals(0, bytes[2].toInt())   // dx MSB
        assertEquals(0, bytes[3].toInt())   // dy = 0
    }

    @Test
    fun wheelMode_accumulatesSubPixelScrollIntoOneStep() {
        listener.setWheelMode(true)
        listener.scrollSpeed = 0.25f
        dispatchDown()

        // Each 1px finger move adds -0.25 scroll: -0.25 → 0, -0.50 → 0, -0.75 → -1
        dispatchMove(801f, 600f, eventTime = 20L)
        dispatchMove(802f, 600f, eventTime = 30L)
        assertTrue("sub-pixel scroll must not emit a report yet", sender.sends.isEmpty())

        dispatchMove(803f, 600f, eventTime = 40L)

        assertEquals(1, sender.sends.size)
        val bytes = sender.sends[0].bytes
        assertEquals(0, bytes[5].toInt())          // vScroll = 0
        assertEquals(0xFF.toByte(), bytes[6])      // hScroll = -1
    }

    // ==================== no crash without a mounted listener ====================

    @Test
    fun unmountedListener_producesNoReports() {
        layer.setGestureListener(null)
        dispatchDown()
        dispatchMove(800f, 700f, eventTime = 30L)
        idle(500)

        assertTrue(rawReports.isEmpty())
        assertTrue(sender.sends.isEmpty())
    }
}
