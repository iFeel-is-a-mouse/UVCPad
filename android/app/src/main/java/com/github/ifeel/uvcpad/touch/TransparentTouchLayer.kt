package com.github.ifeel.uvcpad.touch

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.github.ifeel.uvcpad.bt.listeners.ViewListener

/**
 * 全屏透明触控层（DESIGN §3.2 新建）。
 *
 * 职责：叠加在采集画面上，视觉上完全透出底层画面（不绘制任何内容），
 * 把触摸事件原样转发给手势引擎 [ViewListener]（蓝牙连接后由 MainActivity 挂载）。
 *
 * 挂载/卸载：
 * - 蓝牙连接成功 → [setGestureListener] 挂载手势引擎；
 * - 蓝牙断开 → 先置 null（先卸监听再置空 sender，DESIGN §3.7），后续触摸不再产生 HID 报告。
 *
 * 事件模型：无背景、非 clickable，靠重写 [onTouchEvent] 转发并返回 true 接收事件链；
 * 未挂载手势引擎时返回 false，事件自然穿透（不影响 M2 顶部 UI 区域的事件路由）。
 */
class TransparentTouchLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 手势引擎；null = 未连接/已断开，触摸不产生任何报告 */
    private var gestureListener: ViewListener? = null

    /** 蓝牙连接成功后挂载手势引擎；断开时传入 null 卸载（DESIGN §3.7：先卸监听再置空 sender） */
    fun setGestureListener(listener: ViewListener?) {
        gestureListener = listener
    }

    /** 不绘制任何内容（透明层只收事件不画图，DESIGN §3.2） */
    override fun onDraw(canvas: Canvas) {
        // Intentionally empty: the touch layer is invisible by design
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = gestureListener ?: return false
        return listener.onTouch(this, event)
    }
}
