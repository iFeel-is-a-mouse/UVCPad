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
 * - resolution_w/resolution_h: 分辨率记忆（宽高对，默认 4:3 1872×1404）— 相机 OPENED
 *   回读实际协商尺寸后写入（与"如实显示"逻辑一致，回退尺寸也如实记忆）；下次启动
 *   getCameraRequest() 直接请求记忆值，首次启动默认 4:3 [uvcpad-default-4by3-mem]
 * - last_device_address: 最近成功连接过的蓝牙设备地址（null=无记忆，首次自动连接回退
 *   mpluggedDevice）— 多设备场景自动连接优先"最近连接"而非系统返回的"最早配对"
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

    /** [uvcpad-default-4by3-mem] 记忆的分辨率宽度（默认 4:3 的 1872） */
    var resolutionW: Int
        get() = prefs.getInt(KEY_RESOLUTION_W, DEFAULT_RESOLUTION_W)
        set(value) {
            prefs.edit().putInt(KEY_RESOLUTION_W, value).apply()
        }

    /** [uvcpad-default-4by3-mem] 记忆的分辨率高度（默认 4:3 的 1404） */
    var resolutionH: Int
        get() = prefs.getInt(KEY_RESOLUTION_H, DEFAULT_RESOLUTION_H)
        set(value) {
            prefs.edit().putInt(KEY_RESOLUTION_H, value).apply()
        }

    /**
     * [uvcpad-default-4by3-mem] 宽高对一次性写入：避免两次单独 putInt 之间进程被杀
     * 导致记忆的宽高不成对（如 1872×1080 这种不存在的组合）。
     */
    fun saveResolution(width: Int, height: Int) {
        prefs.edit().putInt(KEY_RESOLUTION_W, width).putInt(KEY_RESOLUTION_H, height).apply()
    }

    /** [uvcpad-last-device] 最近成功连接过的蓝牙设备地址；null=无记忆（首次自动连接回退 mpluggedDevice） */
    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
        set(value) {
            // SharedPreferences 语义：putString(key, null) 等价于移除该 key（清除记忆）
            prefs.edit().putString(KEY_LAST_DEVICE_ADDRESS, value).apply()
        }

    companion object {
        const val KEY_SPEED_LEVEL = "speed_level"
        const val KEY_AUTO_PAIR = "auto_pair"
        const val KEY_SCREEN_ON = "screen_on"
        const val KEY_AUTO_HIDE_MS = "auto_hide_ms"
        const val KEY_RESOLUTION_W = "resolution_w"
        const val KEY_RESOLUTION_H = "resolution_h"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"

        const val DEFAULT_SPEED_LEVEL = 4
        const val DEFAULT_AUTO_HIDE_MS = 4000L
        // 首次启动默认 4:3（1872×1404），与 MainActivity.MODE_4BY3_W/H 一致
        const val DEFAULT_RESOLUTION_W = 1872
        const val DEFAULT_RESOLUTION_H = 1404
    }
}
