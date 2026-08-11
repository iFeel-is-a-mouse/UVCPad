package com.github.ifeel.uvcpad

import android.app.Application

/**
 * App entry point: registers the global uncaught exception handler (CrashHandler).
 *
 * Registered in Application rather than MainActivity.onCreate because crashes that happen
 * before the Activity is created (e.g. Application/resource initialization, early UVC open
 * flow) must also be captured — the earlier the registration, the wider the crash coverage.
 * (改造自 hdmi2mp Hdmi2mpApplication：仅类名/包名改动，DESIGN §4.1)
 */
class UvcpadApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
