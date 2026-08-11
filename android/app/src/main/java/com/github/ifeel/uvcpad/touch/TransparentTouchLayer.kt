package com.github.ifeel.uvcpad.touch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.github.ifeel.uvcpad.bt.listeners.ViewListener

/**
 * 透明触控层（DESIGN §3.2 新建；[uvcpad-touch-align] 需求：触控区域 = 显示区域）。
 *
 * 职责：叠加在采集画面上，视觉上完全透出底层画面（不绘制任何内容），
 * 把触摸事件原样转发给手势引擎 [ViewListener]（蓝牙连接后由 MainActivity 挂载）。
 *
 * 触控区域：[alignToDisplayRect] 把本层收缩到采集画面实际显示矩形（= AspectRatioTextureView
 * 的布局 bounds，AUSBC 按视频宽高比 fit-inside 自缩放）。显示区域外的触摸落在非 clickable 的
 * cameraViewContainer 上被框架直接丢弃 → 不产生任何 HID 事件；显示区域内的触摸才进入本层。
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

    /**
     * 触控区域对齐（[uvcpad-touch-align]）：把本层收缩到采集画面实际显示矩形。
     *
     * [rect] 为相对父容器（rootLayout）的坐标；由 MainActivity 在每次布局变化后调用
     * （首次布局 / switchMode 分辨率切换 / 旋转重建，见 DESIGN §3.2）。零尺寸
     * （width/height = 0）表示暂无可对齐的显示区域（相机未打开/视图未布局）：触控层退化为
     * 0×0，任何触摸都落不到本层 → 不响应、不产生 HID 事件。
     *
     * 值未变化时直接返回，避免无谓的重新布局（布局监听高频触发时会退化为空操作）。
     */
    fun alignToDisplayRect(rect: Rect) {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val width = rect.width()
        val height = rect.height()
        if (lp.leftMargin == rect.left && lp.topMargin == rect.top &&
            lp.width == width && lp.height == height
        ) {
            return
        }
        lp.leftMargin = rect.left
        lp.topMargin = rect.top
        lp.width = width
        lp.height = height
        // [uvcpad-touch-align-fix] layoutParams 写入会触发一次布局 → OnGlobalLayoutListener 再次回调
        // syncTouchLayerBounds；此时值已相同会在上方直接返回，形成最多 2 次触发的无害循环（无死循环风险）。
        layoutParams = lp
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
