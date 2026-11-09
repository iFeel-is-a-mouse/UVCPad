package com.github.ifeel.uvcpad.testutil

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import com.github.ifeel.uvcpad.bt.senders.RelativeMouseSender

/**
 * Test double for [RelativeMouseSender] that captures every HID report at the [emitReport] choke point.
 *
 * The production default implementation still guards on BLUETOOTH_CONNECT and calls
 * BluetoothHidDevice.sendReport; overriding it lets the tests assert the exact 7-byte payloads without a
 * Bluetooth stack (and without needing the S+ permission path). The bytes are copied because the sender
 * reuses a single report ByteArray.
 */
class RecordingSender(
    hidDevice: BluetoothHidDevice,
    host: BluetoothDevice
) : RelativeMouseSender(hidDevice, host) {

    data class SentReport(val reportId: Int, val bytes: ByteArray)

    val sends = mutableListOf<SentReport>()

    override fun emitReport(reportId: Int, bytes: ByteArray): Boolean {
        sends += SentReport(reportId, bytes.copyOf())
        return true
    }
}
