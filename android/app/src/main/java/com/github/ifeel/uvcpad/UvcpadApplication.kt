package com.github.ifeel.uvcpad

import android.app.Application

/**
 * App entry point: registers the global uncaught exception handler (CrashHandler).
 *
 * Registered in Application rather than MainActivity.onCreate because crashes that happen
 * before the Activity is created (e.g. Application/resource initialization, early UVC open
 * flow) must also be captured — the earlier the registration, the wider the crash coverage.
 * (Adapted from hdmi2mp Hdmi2mpApplication: only class name/package name changed, DESIGN §4.1)
 */
class UvcpadApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
