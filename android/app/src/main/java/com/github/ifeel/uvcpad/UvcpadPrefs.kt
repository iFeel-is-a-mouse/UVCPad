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
 * - resolution_mode: 分辨率记忆（模式枚举 0=4:3, 1=16:9，默认 0=4:3）— 记忆**用户选择的
 *   模式**而非硬件回退结果 [uvcpad-resolution-mode]：切换按钮点击即写入（写用户选择）；
 *   相机 OPENED 回读的实际协商尺寸（含回退值）只用于显示，不再回写记忆 → 换硬件后
 *   下次启动仍按记忆模式请求预设（16:9→1920×1080、4:3→1872×1404），硬件不支持时
 *   回退如实显示但记忆不变。旧版 resolution_w/resolution_h（宽高对）在首次读时一次性
 *   迁移：按宽高比归类（交叉相乘 w*3 vs h*4）写入模式并移除旧 key（见 migrateResolutionMode）
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

    init {
        // [uvcpad-resolution-mode] 旧数据迁移在构造时执行一次（旧 key 移除后天然幂等）：
        // 首次读之前完成，后续构造不再触发。
        migrateResolutionMode()
    }

    /**
     * [uvcpad-resolution-mode] 记忆的分辨率模式枚举（MODE_4_3 / MODE_16_9，默认 4:3）。
     * 语义：记忆用户选择的模式（切换按钮点击时写入），不记忆相机 OPENED 回读的实际
     * 协商尺寸（含硬件回退值）——换硬件后记忆不变，下次启动按模式请求预设。
     */
    var resolutionMode: Int
        get() = prefs.getInt(KEY_RESOLUTION_MODE, DEFAULT_RESOLUTION_MODE)
        set(value) {
            prefs.edit().putInt(KEY_RESOLUTION_MODE, value).apply()
        }

    /**
     * [uvcpad-resolution-mode] 旧版宽高对 → 模式枚举一次性迁移。
     *
     * 旧版本（uvcpad-default-4by3-mem）用 resolution_w/resolution_h 记忆"相机 OPENED 回读的
     * 实际协商尺寸"（含硬件回退值，如 1600×1200）。新版本改为记忆用户选择的模式，故迁移：
     * 检测旧 key 存在 → 按宽高比归类 → 写入 resolution_mode → 移除旧 key。
     *
     * 归类规则（交叉相乘 w*3 vs h*4，与 MainActivity.isSixteenNine 同源语义）：
     * w*3 > h*4 表示宽比 4:3 更宽 → 16:9 档；否则（4:3 及更窄，含 1600×1200/1872×1404）→ 4:3 档。
     * 迁移后旧 key 已移除，重复执行直接返回，天然幂等。
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
        const val KEY_RESOLUTION_MODE = "resolution_mode"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"

        // [uvcpad-resolution-mode] 分辨率模式枚举：0=4:3（默认），1=16:9
        const val MODE_4_3 = 0
        const val MODE_16_9 = 1

        const val DEFAULT_SPEED_LEVEL = 4
        const val DEFAULT_AUTO_HIDE_MS = 4000L
        // 首次启动默认 4:3（1872×1404），与 MainActivity.MODE_4BY3_W/H 一致
        const val DEFAULT_RESOLUTION_MODE = MODE_4_3

        // 旧版宽高对 key（仅迁移用，不再读写）：
        // [uvcpad-resolution-mode] 旧 key 保留在常量中供 migrateResolutionMode() 检测/移除
        private const val KEY_LEGACY_RESOLUTION_W = "resolution_w"
        private const val KEY_LEGACY_RESOLUTION_H = "resolution_h"
        // 旧数据缺失单个 key 时的回退值（4:3 档 1872×1404）
        private const val DEFAULT_LEGACY_RESOLUTION_W = 1872
        private const val DEFAULT_LEGACY_RESOLUTION_H = 1404
    }
}
