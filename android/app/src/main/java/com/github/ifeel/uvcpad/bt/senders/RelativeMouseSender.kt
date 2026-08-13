package com.github.ifeel.uvcpad.bt.senders

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.ifeel.uvcpad.bt.BluetoothController
import com.github.ifeel.uvcpad.bt.reports.ScrollableTrackpadMouseReport
import java.nio.ByteBuffer

@Suppress("MemberVisibilityCanBePrivate")
open class RelativeMouseSender(
    val hidDevice: BluetoothHidDevice,
    val host: BluetoothDevice

) {
    val mouseReport = ScrollableTrackpadMouseReport()

    /** [uvcpad-fix-p3] Reuse a single Handler instead of a new Timer().schedule() per click (sendRightClick releases the button) */
    private val clickHandler = Handler(Looper.getMainLooper())

    protected open fun sendMouse() {
        // [uvcpad-fix-p2] Silently drop when the S+ BLUETOOTH_CONNECT permission is revoked, avoiding a sendReport SecurityException crash
        if (!BluetoothController.hasConnectPermission()) return
        try {
            if (!hidDevice.sendReport(host, ScrollableTrackpadMouseReport.ID, mouseReport.bytes)) {
                Log.e(TAG, "Report wasn't sent")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendReport blocked: no BLUETOOTH_CONNECT", e)
            return
        }
        // Zero movement/scroll fields after send; preserve button state for drag support
        mouseReport.dxLsb = 0
        mouseReport.dxMsb = 0
        mouseReport.dyLsb = 0
        mouseReport.dyMsb = 0
        mouseReport.vScroll = 0
        mouseReport.hScroll = 0
    }

    fun sendTestClick() {
        mouseReport.leftButton = true
        sendMouse()
        mouseReport.leftButton = false
        sendMouse()
    }

    fun sendLeftClickOn() {
        mouseReport.reset()
        mouseReport.leftButton = true
        sendMouse()
    }
    fun sendLeftClickOff() {
        mouseReport.dxLsb = 0
        mouseReport.dxMsb = 0
        mouseReport.dyLsb = 0
        mouseReport.dyMsb = 0
        mouseReport.vScroll = 0
        mouseReport.hScroll = 0
        mouseReport.leftButton = false
        sendMouse()
    }

    fun sendRightClick() {
        mouseReport.reset()
        mouseReport.rightButton = true
        sendMouse()
        // [uvcpad-fix-p3] Reuse clickHandler instead of creating a new Timer thread per click (saves one thread per click)
        clickHandler.postDelayed({
            mouseReport.dxLsb = 0
            mouseReport.dxMsb = 0
            mouseReport.dyLsb = 0
            mouseReport.dyMsb = 0
            mouseReport.vScroll = 0
            mouseReport.hScroll = 0
            mouseReport.rightButton = false
            sendMouse()
        }, 50L)
    }

    /** Move with left button held for drag operations. */
    fun sendDragMove(dx: Int, dy: Int) {
        mouseReport.leftButton = true
        sendMouseMove(dx, dy)
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        var dxInt = dx
        var dyInt = dy

        // Clamp to HID range (±2047)
        if (dxInt > 2047) dxInt = 2047
        if (dxInt < -2047) dxInt = -2047
        if (dyInt > 2047) dyInt = 2047
        if (dyInt < -2047) dyInt = -2047

        val bytesArrX = ByteArray(2) { 0 }
        val buffX: ByteBuffer = ByteBuffer.wrap(bytesArrX)
        buffX.putShort(dxInt.toShort())

        val bytesArrY = ByteArray(2) { 0 }
        val buffY: ByteBuffer = ByteBuffer.wrap(bytesArrY)
        buffY.putShort(dyInt.toShort())

        mouseReport.dxMsb = bytesArrX[0]
        mouseReport.dxLsb = bytesArrX[1]
        mouseReport.dyMsb = bytesArrY[0]
        mouseReport.dyLsb = bytesArrY[1]

        // Note: does NOT reset buttons/scroll – preserves drag state
        sendMouse()
    }

    fun sendScroll(vscroll: Int, hscroll: Int) {
        // [uvcpad-fix-p3] Per-frame 4 Log.i calls removed (high-frequency noise); no dead code/comment blocks
        mouseReport.vScroll = vscroll.toByte()
        mouseReport.hScroll = hscroll.toByte()
        sendMouse()
    }

    companion object {
        const val TAG = "TrackPadSender"
    }

}
