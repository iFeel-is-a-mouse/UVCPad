package com.github.ifeel.uvcpad.bt.senders

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log
import com.github.ifeel.uvcpad.bt.reports.ScrollableTrackpadMouseReport
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.schedule

@Suppress("MemberVisibilityCanBePrivate")
open class RelativeMouseSender(
    val hidDevice: BluetoothHidDevice,
    val host: BluetoothDevice

) {
    val mouseReport = ScrollableTrackpadMouseReport()
    var previousvscroll :Int=0
    var previoushscroll :Int =0

    protected open fun sendMouse() {
        if (!hidDevice.sendReport(host, ScrollableTrackpadMouseReport.ID, mouseReport.bytes)) {
            Log.e(TAG, "Report wasn't sent")
        }
        // Zero movement/scroll fields after send; preserve button state for drag support
        mouseReport.dxLsb = 0
        mouseReport.dxMsb = 0
        mouseReport.dyLsb = 0
        mouseReport.dyMsb = 0
        mouseReport.vScroll = 0
        mouseReport.hScroll = 0
    }

    fun sendTestMouseMove() {
        mouseReport.dxLsb = 20
        mouseReport.dyLsb = 20
        mouseReport.dxMsb = 20
        mouseReport.dyMsb = 20
        sendMouse()
    }

    fun sendTestClick() {
        mouseReport.leftButton = true
        sendMouse()
        mouseReport.leftButton = false
        sendMouse()
//        Timer().schedule(20L) {
//
//        }
    }
    fun sendDoubleTapClick() {
        mouseReport.leftButton = true
        sendMouse()
        Timer().schedule(100L) {
            mouseReport.leftButton = false
            sendMouse()
            Timer().schedule(100L) {
                mouseReport.leftButton = true
                sendMouse()
                Timer().schedule(100L) {
                    mouseReport.leftButton = false
                    sendMouse()
                }




            }
        }
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
    fun sendRightClickOn() {
        mouseReport.reset()
        mouseReport.rightButton = true
        sendMouse()
    }

    fun sendRightClickOff() {
        mouseReport.dxLsb = 0
        mouseReport.dxMsb = 0
        mouseReport.dyLsb = 0
        mouseReport.dyMsb = 0
        mouseReport.vScroll = 0
        mouseReport.hScroll = 0
        mouseReport.rightButton = false
        sendMouse()
    }

    fun sendRightClick() {
        mouseReport.reset()
        mouseReport.rightButton = true
        sendMouse()
        Timer().schedule(50L) {
            mouseReport.dxLsb = 0
            mouseReport.dxMsb = 0
            mouseReport.dyLsb = 0
            mouseReport.dyMsb = 0
            mouseReport.vScroll = 0
            mouseReport.hScroll = 0
            mouseReport.rightButton = false
            sendMouse()
        }
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

    fun sendScroll(vscroll:Int,hscroll:Int){

        var hscrollmutable=0
        var vscrollmutable =0

        hscrollmutable=hscroll
        vscrollmutable= vscroll

//        var dhscroll= hscrollmutable-previoushscroll
//        var dvscroll= vscrollmutable-previousvscroll
//
//        dhscroll = Math.abs(dhscroll)
//        dvscroll = Math.abs(dvscroll)
//        if(dvscroll>=dhscroll)
//        {
//            hscrollmutable=0
//
//        }
//        else
//        {
//            vscrollmutable=0
//        }
        var vs:Int =(vscrollmutable)
        var hs:Int =(hscrollmutable)
        Log.i("vscroll ",vscroll.toString())
        Log.i("vs ",vs.toString())
        Log.i("hscroll ",hscroll.toString())
        Log.i("hs ",hs.toString())


        mouseReport.vScroll=vs.toByte()
        mouseReport.hScroll= hs.toByte()

        sendMouse()

//        previousvscroll=-1*vscroll
//        previoushscroll=hscroll


    }




    companion object {
        const val TAG = "TrackPadSender"
    }

}