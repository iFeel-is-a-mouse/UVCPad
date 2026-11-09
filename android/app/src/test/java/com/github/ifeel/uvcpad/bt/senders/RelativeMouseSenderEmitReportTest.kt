package com.github.ifeel.uvcpad.bt.senders

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [uvcpad-rotate-test2] Production [RelativeMouseSender.emitReport] path (previously untested — the existing
 * sender tests override the choke point in [com.github.ifeel.uvcpad.testutil.RecordingSender]).
 *
 * BluetoothHidDevice is a final class → Mockito 5 inline mock maker. At SDK 28 the BLUETOOTH_CONNECT guard
 * (`hasConnectPermission`) short-circuits to true, so the sendReport paths are exercised directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelativeMouseSenderEmitReportTest {

    private val hidDevice: BluetoothHidDevice = mock(BluetoothHidDevice::class.java)
    private val host: BluetoothDevice = mock(BluetoothDevice::class.java)
    private val sender = ExposedSender(hidDevice, host)

    private val reportId = 4
    private val payload = ByteArray(7)

    /** Production emitReport is protected: same-package tests cannot call it on a plain instance, so expose it. */
    private class ExposedSender(hidDevice: BluetoothHidDevice, host: BluetoothDevice) :
        RelativeMouseSender(hidDevice, host) {
        fun emit(reportId: Int, bytes: ByteArray): Boolean = emitReport(reportId, bytes)
    }

    @Test
    fun securityExceptionFromSendReport_isSwallowedAndDropped() {
        `when`(hidDevice.sendReport(host, reportId, payload))
            .thenThrow(SecurityException("BLUETOOTH_CONNECT revoked"))

        // Must not crash; the report is reported as not delivered so sendMouse() keeps its fields.
        assertFalse(sender.emit(reportId, payload))
        verify(hidDevice, times(1)).sendReport(host, reportId, payload)
    }

    @Test
    fun sendReportReturningFalse_doesNotCrashAndIsNotRetried() {
        `when`(hidDevice.sendReport(host, reportId, payload)).thenReturn(false)

        // A false return still counts as "handed to the stack" (previous behaviour: fields are zeroed).
        assertTrue(sender.emit(reportId, payload))
        verify(hidDevice, times(1)).sendReport(host, reportId, payload)
        verify(hidDevice, times(1)).sendReport(any(BluetoothDevice::class.java), anyInt(), any(ByteArray::class.java))
    }

    @Test
    fun successfulSend_returnsTrueAndSendsExactlyOnce() {
        `when`(hidDevice.sendReport(host, reportId, payload)).thenReturn(true)

        assertTrue(sender.emit(reportId, payload))
        verify(hidDevice, times(1)).sendReport(host, reportId, payload)
    }

    @Test
    @Config(sdk = [31])
    fun revokedConnectPermission_dropsBeforeTouchingTheStack() {
        // SDK 31 = S: the guard matters. Without an initialised BluetoothController context the permission
        // check is not granted, so emitReport must return false without calling sendReport at all.
        assertFalse(sender.emit(reportId, payload))
        verify(hidDevice, never()).sendReport(any(BluetoothDevice::class.java), anyInt(), any(ByteArray::class.java))
    }
}
