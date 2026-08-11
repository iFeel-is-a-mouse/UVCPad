package com.github.ifeel.uvcpad

import android.content.Context

/**
 * SharedPreferences wrapper for uvcpad preferences (DESIGN §1.1 新建).
 *
 * Keys follow KeysJoy conventions so the persisted values stay compatible:
 * - speed_level: 1..5, default 4 (= SpeedLevel.DEFAULT, 1.0f) — read at connection time and
 *   applied to ViewListener.mouseSpeed/scrollSpeed (DESIGN §3.6: 跨模块变换走 SpeedLevel 覆盖)
 * - auto_pair: default true — BluetoothController.autoPairFlag + startAutoReconnect
 * - screen_on: default true — FLAG_KEEP_SCREEN_ON
 * - auto_hide_ms: default 4000 — 按键栏自动隐藏时长（M2 使用，M1 仅预留读写）
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

    companion object {
        const val KEY_SPEED_LEVEL = "speed_level"
        const val KEY_AUTO_PAIR = "auto_pair"
        const val KEY_SCREEN_ON = "screen_on"
        const val KEY_AUTO_HIDE_MS = "auto_hide_ms"

        const val DEFAULT_SPEED_LEVEL = 4
        const val DEFAULT_AUTO_HIDE_MS = 4000L
    }
}
