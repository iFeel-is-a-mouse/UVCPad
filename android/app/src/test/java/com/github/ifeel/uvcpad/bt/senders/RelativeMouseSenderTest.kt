package com.github.ifeel.uvcpad.bt.senders

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import com.github.ifeel.uvcpad.testutil.RecordingSender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Simulated output (HID reports) through [RelativeMouseSender].
 *
 * [RecordingSender] overrides the [RelativeMouseSender.emitReport] choke point (the production default is the
 * original inline `BluetoothHidDevice.sendReport` block, unchanged) so every payload handed to the Bluetooth
 * stack can be asserted byte-for-byte: 7 bytes, report ID 4, little-endian dx/dy, two's-complement scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelativeMouseSenderTest {

    private lateinit var sender: RecordingSender

    private val hidDevice: BluetoothHidDevice = mock(BluetoothHidDevice::class.java)
    private val host: BluetoothDevice = mock(BluetoothDevice::class.java)

    @Before
    fun setUp() {
        sender = RecordingSender(hidDevice, host)
    }

    private fun last() = sender.sends.last()

    private fun assertReport(
        report: RecordingSender.SentReport,
        expected: ByteArray
    ) {
        assertEquals("report ID", 4, report.reportId)
        assertArrayEquals("7-byte payload", expected, report.bytes)
    }

    // ==================== click ====================

    @Test
    fun tap_sendsButtonDownThenRelease() {
        sender.sendTestClick()

        assertEquals(2, sender.sends.size)
        assertReport(sender.sends[0], byteArrayOf(0x01, 0, 0, 0, 0, 0, 0))
        assertReport(sender.sends[1], ByteArray(7))
    }

    @Test
    fun leftClickOnAndOff_holdAndReleaseTheButton() {
        sender.sendLeftClickOn()
        assertReport(last(), byteArrayOf(0x01, 0, 0, 0, 0, 0, 0))

        sender.sendLeftClickOff()
        assertReport(last(), ByteArray(7))
    }

    @Test
    fun rightClick_releasesTheButtonAfterTheDelay() {
        sender.sendRightClick()
        assertReport(last(), byteArrayOf(0x02, 0, 0, 0, 0, 0, 0))

        // clickHandler.postDelayed(…, 50ms) → advance the main looper clock
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(60))

        assertEquals(2, sender.sends.size)
        assertReport(last(), ByteArray(7))
    }

    // ==================== movement: little-endian 16-bit ====================

    @Test
    fun move_encodesDxDyAsLittleEndian16Bit() {
        sender.sendMouseMove(-46, 12)

        assertReport(
            last(),
            byteArrayOf(
                0x00,
                0xD2.toByte(), 0xFF.toByte(),   // dx = -46 = 0xFFD2
                0x0C, 0x00,                     // dy =  12 = 0x000C
                0x00, 0x00
            )
        )
    }

    @Test
    fun move_clampsPositiveOverflowTo2047() {
        sender.sendMouseMove(5000, 0)

        assertReport(
            last(),
            byteArrayOf(
                0x00,
                0xFF.toByte(), 0x07,            // 2047 = 0x07FF
                0x00, 0x00,
                0x00, 0x00
            )
        )
    }

    @Test
    fun move_clampsNegativeOverflowToMinus2047() {
        sender.sendMouseMove(0, -5000)

        assertReport(
            last(),
            byteArrayOf(
                0x00,
                0x00, 0x00,
                0x01, 0xF8.toByte(),            // -2047 = 0xF801
                0x00, 0x00
            )
        )
    }

    @Test
    fun move_keepsTheExactClampBoundary() {
        sender.sendMouseMove(2048, -2048)

        assertReport(
            last(),
            byteArrayOf(
                0x00,
                0xFF.toByte(), 0x07,            // 2047
                0x01, 0xF8.toByte(),            // -2047
                0x00, 0x00
            )
        )

        sender.sendMouseMove(2047, -2047)
        assertArrayEquals(
            sender.sends[sender.sends.size - 2].bytes,
            last().bytes
        )
    }

    // ==================== scroll: 8-bit two's complement ====================

    @Test
    fun scroll_encodesTwosComplementBytes() {
        sender.sendScroll(-25, 3)

        assertReport(
            last(),
            byteArrayOf(
                0x00,
                0x00, 0x00,
                0x00, 0x00,
                0xE7.toByte(),                  // -25 = 0xE7
                0x03                            //   3 = 0x03
            )
        )
    }

    @Test
    fun scroll_encodesSignedExtremes() {
        sender.sendScroll(127, -128)

        assertReport(
            last(),
            byteArrayOf(
                0x00,
                0x00, 0x00,
                0x00, 0x00,
                0x7F,                           // 127
                0x80.toByte()                   // -128
            )
        )
    }

    // ==================== state after send ====================

    @Test
    fun send_zeroesMovementAndScrollButPreservesButtons() {
        sender.mouseReport.leftButton = true
        sender.sendMouseMove(10, -10)
        sender.sendScroll(5, 5)

        assertEquals(0, sender.mouseReport.dxLsb.toInt())
        assertEquals(0, sender.mouseReport.dxMsb.toInt())
        assertEquals(0, sender.mouseReport.dyLsb.toInt())
        assertEquals(0, sender.mouseReport.dyMsb.toInt())
        assertEquals(0, sender.mouseReport.vScroll.toInt())
        assertEquals(0, sender.mouseReport.hScroll.toInt())
        assertTrue("drag state must survive a movement report", sender.mouseReport.leftButton)
    }

    @Test
    fun dragMove_keepsLeftButtonHeld() {
        sender.sendDragMove(5, -5)

        val bytes = last().bytes
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
        assertEquals(5, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(0xFB.toByte(), bytes[3])   // -5
        assertEquals(0xFF.toByte(), bytes[4])
        assertTrue(sender.mouseReport.leftButton)
    }

    @Test
    fun everyReportHasTheSevenByteMouseLayout() {
        sender.sendTestClick()
        sender.sendMouseMove(1, 1)
        sender.sendScroll(1, 1)
        sender.sendLeftClickOn()
        sender.sendLeftClickOff()

        assertTrue(sender.sends.isNotEmpty())
        sender.sends.forEach {
            assertEquals(4, it.reportId)
            assertEquals(7, it.bytes.size)
        }
        assertFalse(sender.sends.all { it.bytes.all { b -> b.toInt() == 0 } })
    }
}
