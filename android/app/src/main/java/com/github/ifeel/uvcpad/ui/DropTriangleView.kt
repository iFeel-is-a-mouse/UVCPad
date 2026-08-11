package com.github.ifeel.uvcpad.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * 下拉三角（DESIGN §3.3 新建）：顶部居中的唯一常驻 UI。
 *
 * 事件豁免（M2 验收关键项）：ACTION_DOWN 消费（返回 true）→ 事件流归三角所有，
 * 不穿透进触控手势层 → 点三角不会误触成 tap→左键、不产生任何鼠标报告。
 * ACTION_MOVE 滑出热区（本 View bounds）标记取消；ACTION_UP 在热区内 → [onToggle]()。
 *
 * 视觉：小三角（半透明白色 + 阴影），在采集画面上清晰可见；
 * 热区 = 本 View bounds（布局 48dp×48dp，≥ 设计下限，DESIGN §6.3）。
 */
class DropTriangleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 热区内抬起回调：MainActivity 用它 toggle 按键栏显隐（DESIGN §3.3） */
    var onToggle: (() -> Unit)? = null

    /** ACTION_MOVE 滑出热区后置 true：ACTION_UP 不再触发 toggle */
    private var cancelled = false

    private val trianglePath = Path()
    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 半透明白色（约 60% 白）+ 轻微阴影，保证亮/暗画面上都可见
        color = Color.argb(0x99, 0xFF, 0xFF, 0xFF)
        setShadowLayer(dp(6f), 0f, dp(2f), Color.argb(0x66, 0x00, 0x00, 0x00))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 小三角：24dp 宽 × 12dp 高，顶部居中，指向下方（下拉指示）
        val tw = dp(24f)
        val th = dp(12f)
        val cx = w / 2f
        val top = dp(8f)
        trianglePath.reset()
        trianglePath.moveTo(cx - tw / 2f, top)
        trianglePath.lineTo(cx + tw / 2f, top)
        trianglePath.lineTo(cx, top + th)
        trianglePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(trianglePath, trianglePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelled = false
                return true // 消费：事件流归三角，不穿透触控层
            }
            MotionEvent.ACTION_MOVE -> {
                // 事件归属由 ACTION_DOWN 决定：滑出热区后 MOVE 仍派发到本层，标记取消
                if (!isInsideHotZone(event.x, event.y)) cancelled = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val trigger = !cancelled
                cancelled = false
                if (trigger) onToggle?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelled = true
                return true
            }
        }
        return true
    }

    /** 热区 = 本 View bounds（48dp×48dp） */
    private fun isInsideHotZone(x: Float, y: Float): Boolean =
        x in 0f..width.toFloat() && y in 0f..height.toFloat()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
