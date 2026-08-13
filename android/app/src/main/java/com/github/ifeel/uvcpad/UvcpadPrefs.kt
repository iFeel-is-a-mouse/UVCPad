package com.github.ifeel.uvcpad

import android.content.Context

/**
 * SharedPreferences wrapper for uvcpad preferences (new in DESIGN §1.1).
 *
 * Keys follow KeysJoy conventions so the persisted values stay compatible:
 * - speed_level: 1..5, default 4 (= SpeedLevel.DEFAULT, 1.0f) — read at connection time and
 *   applied to ViewListener.mouseSpeed/scrollSpeed (DESIGN §3.6: cross-module transformation goes through the SpeedLevel override)
 * - auto_pair: default true — BluetoothController.autoPairFlag + startAutoReconnect
 * - screen_on: default true — FLAG_KEEP_SCREEN_ON
 * - auto_hide_ms: default 4000 — key bar auto-hide duration (used by M2; M1 only reserves the read/write)
 * - resolution_mode: resolution memory (mode enum 0=4:3, 1=16:9, default 0=4:3) — remembers the **user-selected
 *   mode** rather than the hardware fallback result [uvcpad-resolution-mode]: written immediately on switch-button tap (writes the user choice);
 *   the actually negotiated size read back on camera OPENED (including fallback values) is only used for display and is never written back
 *   to memory → after swapping hardware, the next launch still requests the preset per the remembered mode (16:9→1920×1080, 4:3→1872×1404);
 *   when unsupported by the hardware, the fallback is shown truthfully but the memory stays unchanged. The legacy resolution_w/resolution_h
 *   (width/height pair) is migrated once on first read: classified by aspect ratio (cross-multiply w*3 vs h*4), the mode is written and the old
 *   keys are removed (see migrateResolutionMode)
 * - last_device_address: address of the most recently successfully connected Bluetooth device (null=no memory, first auto-connect falls back
 *   to mpluggedDevice) — in multi-device scenarios auto-connect prefers the "most recent connection" over the system's "earliest paired"
 *   [uvcpad-last-device]
 */
class UvcpadPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("uvcpad_prefs", Context.MODE_PRIVATE)

    var speedLevel: Int
        get() = prefs.getInt(KEY_SPEED_LEVEL, DEFAULT_SPEED_LEVEL)
        set(value) {
            prefs.edit().putInt(KEY_SPEED_LEVEL, value).apply()
        }

    var autoPair: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAIR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_PAIR, value).apply()
        }

    var screenOn: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_ON, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SCREEN_ON, value).apply()
        }

    var autoHideMs: Long
        get() = prefs.getLong(KEY_AUTO_HIDE_MS, DEFAULT_AUTO_HIDE_MS)
        set(value) {
            prefs.edit().putLong(KEY_AUTO_HIDE_MS, value).apply()
        }

    init {
        // [uvcpad-resolution-mode] The legacy-data migration runs once in the constructor (naturally idempotent once the old keys are removed):
        // it completes before the first read, and later constructions do not trigger it again.
        migrateResolutionMode()
    }

    /**
     * [uvcpad-resolution-mode] Remembered resolution mode enum (MODE_4_3 / MODE_16_9, default 4:3).
     * Semantics: remembers the user-selected mode (written when the switch button is tapped); does not remember the actual
     * negotiated size read back on camera OPENED (including hardware fallback values) — memory stays unchanged after hardware
     * swaps, and the next launch requests the preset per the mode.
     */
    var resolutionMode: Int
        get() = prefs.getInt(KEY_RESOLUTION_MODE, DEFAULT_RESOLUTION_MODE)
        set(value) {
            prefs.edit().putInt(KEY_RESOLUTION_MODE, value).apply()
        }

    /**
     * [uvcpad-resolution-mode] One-time migration from the legacy width/height pair to the mode enum.
     *
     * Legacy versions (uvcpad-default-4by3-mem) used resolution_w/resolution_h to remember the "actual negotiated size
     * read back on camera OPENED" (including hardware fallback values, e.g. 1600×1200). Newer versions remember the
     * user-selected mode instead, hence the migration: detect the old keys → classify by aspect ratio → write resolution_mode → remove the old keys.
     *
     * Classification rule (cross-multiply w*3 vs h*4, same semantics as MainActivity.isSixteenNine):
     * w*3 > h*4 means the width is wider than 4:3 → 16:9 bucket; otherwise (4:3 and narrower, incl. 1600×1200/1872×1404) → 4:3 bucket.
     * After migration the old keys are gone, so re-running simply returns — naturally idempotent.
     */
    private fun migrateResolutionMode() {
        if (!prefs.contains(KEY_LEGACY_RESOLUTION_W) && !prefs.contains(KEY_LEGACY_RESOLUTION_H)) {
            return
        }
        val oldW = prefs.getInt(KEY_LEGACY_RESOLUTION_W, DEFAULT_LEGACY_RESOLUTION_W)
        val oldH = prefs.getInt(KEY_LEGACY_RESOLUTION_H, DEFAULT_LEGACY_RESOLUTION_H)
        val mode = if (oldW > 0 && oldH > 0 && oldW.toLong() * 3 > oldH.toLong() * 4) {
            MODE_16_9
        } else {
            MODE_4_3
        }
        prefs.edit()
            .putInt(KEY_RESOLUTION_MODE, mode)
            .remove(KEY_LEGACY_RESOLUTION_W)
            .remove(KEY_LEGACY_RESOLUTION_H)
            .apply()
    }

    /** [uvcpad-last-device] Address of the most recently successfully connected Bluetooth device; null=no memory (first auto-connect falls back to mpluggedDevice) */
    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
        set(value) {
            // SharedPreferences semantics: putString(key, null) is equivalent to removing that key (clears the memory)
            prefs.edit().putString(KEY_LAST_DEVICE_ADDRESS, value).apply()
        }

    companion object {
        const val KEY_SPEED_LEVEL = "speed_level"
        const val KEY_AUTO_PAIR = "auto_pair"
        const val KEY_SCREEN_ON = "screen_on"
        const val KEY_AUTO_HIDE_MS = "auto_hide_ms"
        const val KEY_RESOLUTION_MODE = "resolution_mode"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"

        // [uvcpad-resolution-mode] Resolution mode enum: 0=4:3 (default), 1=16:9
        const val MODE_4_3 = 0
        const val MODE_16_9 = 1

        const val DEFAULT_SPEED_LEVEL = 4
        const val DEFAULT_AUTO_HIDE_MS = 4000L
        // Default on first launch is 4:3 (1872×1404), consistent with MainActivity.MODE_4BY3_W/H
        const val DEFAULT_RESOLUTION_MODE = MODE_4_3

        // Legacy width/height pair keys (migration only, no longer read/written):
        // [uvcpad-resolution-mode] Old keys are kept in constants for migrateResolutionMode() to detect/remove
        private const val KEY_LEGACY_RESOLUTION_W = "resolution_w"
        private const val KEY_LEGACY_RESOLUTION_H = "resolution_h"
        // Fallback values when a single legacy key is missing (4:3 bucket 1872×1404)
        private const val DEFAULT_LEGACY_RESOLUTION_W = 1872
        private const val DEFAULT_LEGACY_RESOLUTION_H = 1404
    }
}
