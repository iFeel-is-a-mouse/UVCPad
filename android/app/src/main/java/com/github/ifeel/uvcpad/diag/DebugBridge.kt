package com.github.ifeel.uvcpad.diag

/**
 * [uvcpad-t0] Process-wide hand-off between the debug-only broadcast receiver
 * (class + manifest entry exist ONLY in the debug source set) and the running Activity.
 *
 * MainActivity installs the callbacks only when [com.github.ifeel.uvcpad.BuildConfig.DEBUG]
 * is true and clears them in onDestroy, so a release build has no live entry point.
 */
object DebugBridge {

    @Volatile
    var toggleHud: (() -> Unit)? = null

    @Volatile
    var toggleSelfTest: (() -> Unit)? = null

    @Volatile
    var injectGesture: ((String) -> Unit)? = null

    fun clear() {
        toggleHud = null
        toggleSelfTest = null
        injectGesture = null
    }
}
