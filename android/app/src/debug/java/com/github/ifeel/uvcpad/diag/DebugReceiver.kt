package com.github.ifeel.uvcpad.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.ifeel.uvcpad.BuildConfig

/**
 * [uvcpad-t0] Debug-only adb entry points.
 *
 * Declared exported in src/debug/AndroidManifest.xml only; the class is in the debug source
 * set, so a release APK contains neither the receiver declaration nor the class. The
 * [BuildConfig.DEBUG] guard is a second, in-code barrier (defence in depth, as requested).
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        when (intent?.action) {
            ACTION_HUD -> {
                Diag.ui("broadcast=DEBUG_HUD")
                DebugBridge.toggleHud?.invoke()
            }
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
    }

    companion object {
        private const val TAG = "UvcpadDebugReceiver"
        const val ACTION_HUD = "com.github.ifeel.uvcpad.DEBUG_HUD"
    }
}
