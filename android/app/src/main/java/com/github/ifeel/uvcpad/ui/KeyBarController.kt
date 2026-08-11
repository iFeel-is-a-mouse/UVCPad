package com.github.ifeel.uvcpad.ui

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * 按键栏显隐控制（DESIGN §3.4 新建；显隐/防竞态改造自 hdmi2mp showToolbar/hideToolbar/hideGeneration）：
 * - [show]：滑出 + 重置自动隐藏计时（hideGeneration++ 使进行中的隐藏动画失效）；
 * - [hide]：滑回 + 动画结束置 GONE（generation 校验：动画期间被 show 打断则保持可见）；
 * - [resetAutoHideTimer]：任意触摸（三角/按键栏/触控层）调用，重置 [autoHideMs] 计时；
 * - [destroy]：onDestroy 时清除计时器与动画，避免 Handler 泄漏。
 *
 * 初始状态：按键栏 GONE（布局里 visibility="gone"），只有三角常驻；首次 show 从顶边之上滑入。
 */
class KeyBarController(
    private val panel: View,
    private val autoHideMs: Long
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var hideGeneration = 0
    private var visible = false

    val isVisible: Boolean get() = visible

    /** 展开按键栏并重置自动隐藏计时；已在展开状态时仅重置计时（幂等，hdmi2mp 模式） */
    fun show() {
        hideGeneration++
        mainHandler.removeCallbacks(hideRunnable)
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            // 首次展开：从顶边之上滑入（hide() 之后 translationY 已是 -height，无需再设）
            if (panel.translationY == 0f) {
                panel.translationY = -panel.height.toFloat()
            }
        }
        panel.animate().cancel()
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(FADE_IN_MS)
            .start()
        visible = true
        resetAutoHideTimer()
    }

    /** 收起按键栏：滑回顶边之上，动画结束置 GONE（期间被 show 打断则保持可见） */
    fun hide() {
        if (!visible || panel.visibility != View.VISIBLE) return
        visible = false
        mainHandler.removeCallbacks(hideRunnable)
        val gen = hideGeneration
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .translationY(-panel.height.toFloat())
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                // 竞态防护：动画期间用户再次展开（generation 变化）→ 保持可见
                if (hideGeneration == gen && panel.visibility == View.VISIBLE) {
                    panel.visibility = View.GONE
                }
            }
            .start()
    }

    /** 三角点击入口：未展开→展开；已展开→收起（DESIGN §3.3） */
    fun toggle() {
        if (visible) hide() else show()
    }

    /** 任意触摸重置自动隐藏计时（DESIGN §3.4："任意触摸都重置计时"，与 hdmi2mp 一致） */
    fun resetAutoHideTimer() {
        if (!visible) return
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, autoHideMs)
    }

    /** Activity onDestroy 调用：清除计时器与动画（hdmi2mp: removeCallbacksAndMessages） */
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        panel.animate().cancel()
    }

    companion object {
        private const val FADE_IN_MS = 150L
        private const val FADE_OUT_MS = 200L
    }
}
