package com.github.ifeel.uvcpad.bt.reports

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulated output (HID report layout) — [ScrollableTrackpadMouseReport] byte map.
 *
 * Descriptor (DescriptorCollection.MOUSE_RELATIVE_WITH_SCROLL) and report layout under test:
 *   byte 0 : buttons (bit0 = left, bit1 = right, upper 6 bits const/zero)
 *   byte 1 : dx LSB  (16-bit signed, little-endian)
 *   byte 2 : dx MSB
 *   byte 3 : dy LSB
 *   byte 4 : dy MSB
 *   byte 5 : vertical scroll   (8-bit signed, two's complement)
 *   byte 6 : horizontal scroll (8-bit signed, two's complement)
 * total = 7 bytes, report ID = 4 (passed separately to BluetoothHidDevice.sendReport).
 *
 * Pure JVM test: the report is a value class over ByteArray with no Android dependency.
 */
class ScrollableTrackpadMouseReportTest {

    private fun report() = ScrollableTrackpadMouseReport()

    @Test
    fun defaultReport_isSevenZeroBytesWithId4() {
        val bytes = report().bytes
        assertEquals(7, bytes.size)
        assertArrayEquals(ByteArray(7), bytes)
        assertEquals(4, ScrollableTrackpadMouseReport.ID)
    }

    @Test
    fun leftButton_setsBit0Only() {
        val report = report()
        report.leftButton = true
        assertEquals(0x01, report.bytes[0].toInt() and 0xFF)
        assertTrue(report.leftButton)
        assertFalse(report.rightButton)

        report.leftButton = false
        assertEquals(0x00, report.bytes[0].toInt() and 0xFF)
    }

    @Test
    fun rightButton_setsBit1Only() {
        val report = report()
        report.rightButton = true
        assertEquals(0x02, report.bytes[0].toInt() and 0xFF)
        assertTrue(report.rightButton)
        assertFalse(report.leftButton)

        report.rightButton = false
        assertEquals(0x00, report.bytes[0].toInt() and 0xFF)
    }

    @Test
    fun bothButtons_setBothBitsAndClearIndependently() {
        val report = report()
        report.leftButton = true
        report.rightButton = true
        assertEquals(0x03, report.bytes[0].toInt() and 0xFF)

        // Clearing one bit must preserve the other
        report.leftButton = false
        assertEquals(0x02, report.bytes[0].toInt() and 0xFF)

        report.rightButton = false
        assertEquals(0x00, report.bytes[0].toInt() and 0xFF)
    }

    @Test
    fun movementAndScrollFields_haveTheirDescriptorOffsets() {
        val report = report()
        report.dxLsb = 0xD2.toByte()
        report.dxMsb = 0xFF.toByte()
        report.dyLsb = 0x0C
        report.dyMsb = 0x00
        report.vScroll = 0xE7.toByte()
        report.hScroll = 0x03

        assertArrayEquals(
            byteArrayOf(
                0x00,
                0xD2.toByte(), 0xFF.toByte(),
                0x0C, 0x00,
                0xE7.toByte(),
                0x03
            ),
            report.bytes
        )
    }

    @Test
    fun reset_zeroesAllSevenBytes() {
        val report = report()
        report.leftButton = true
        report.rightButton = true
        report.dxLsb = 1
        report.dxMsb = 2
        report.dyLsb = 3
        report.dyMsb = 4
        report.vScroll = 5
        report.hScroll = 6

        report.reset()

        assertArrayEquals(ByteArray(7), report.bytes)
    }

    @Test
    fun byteArrayIsBackingStorage_notACopy() {
        val report = report()
        val bytes = report.bytes
        report.dxLsb = 42
        assertEquals(42, bytes[1].toInt())
    }
}
