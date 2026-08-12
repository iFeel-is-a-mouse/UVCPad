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

    /** [uvcpad-fix-p3] 复用单 Handler 代替每次 new Timer().schedule()（sendRightClick 释放按键） */
    private val clickHandler = Handler(Looper.getMainLooper())

    protected open fun sendMouse() {
        // [uvcpad-fix-p2] S+ BLUETOOTH_CONNECT 被撤销时静默丢弃，避免 sendReport SecurityException 崩溃
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
        // [uvcpad-fix-p3] 复用 clickHandler 替代每次新建 Timer 线程（每击省一个线程）
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
        // [uvcpad-fix-p3] 每帧 4 条 Log.i 已删除（高频噪声）；无死代码/注释块
        mouseReport.vScroll = vscroll.toByte()
        mouseReport.hScroll = hscroll.toByte()
        sendMouse()
    }

    companion object {
        const val TAG = "TrackPadSender"
    }

}
