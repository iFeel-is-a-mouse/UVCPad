package com.github.ifeel.uvcpad.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 按键栏容器（DESIGN §3.4 新建）：横向顶栏（参考 hdmi2mp topOverlay 样式）。
 *
 * 区域事件消费（M2 验收关键项）：落在按键栏边界内的所有触摸被消费，不进触控手势层——
 * - 按钮各自 clickable，DOWN 由按钮消费 → 点菜单按钮不产生鼠标报告；
 * - 非按钮区域由本容器 [onTouchEvent] 消费（返回 true）→ 点菜单空隙同样不穿透。
 *
 * 展开期间栏外仍是触控板：本容器只消费自己边界内的事件，栏外触摸正常进入触控层。
 */
class KeyBarPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** 非按钮区域触摸被消费的回调（MainActivity 用它重置自动隐藏计时，DESIGN §3.4） */
    var onAreaTouch: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onAreaTouch?.invoke()
        }
        return true // 消费按键栏区域内的所有触摸（按钮由子 View 先行消费）
    }
}
