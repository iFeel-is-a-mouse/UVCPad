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
 * Transparent touch layer (new in DESIGN §3.2; [uvcpad-touch-align] requirement: touch area = display area).
 *
 * Responsibility: overlaid on the capture frame, fully transparent visually (draws nothing),
 * forwards touch events as-is to the gesture engine [ViewListener] (mounted by MainActivity after Bluetooth connects).
 *
 * Touch area: [alignToDisplayRect] shrinks this layer to the actual display rectangle of the capture frame (= AspectRatioTextureView's
 * layout bounds; AUSBC scales it fit-inside according to the video aspect ratio). Touches outside the display area land on the non-clickable
 * cameraViewContainer and are dropped by the framework → no HID events; only touches inside the display area reach this layer.
 *
 * Mount/unmount:
 * - Bluetooth connects → [setGestureListener] mounts the gesture engine;
 * - Bluetooth disconnects → set to null first (unmount the listener before nulling the sender, DESIGN §3.7); subsequent touches produce no HID reports.
 *
 * Event model: no background, non-clickable; forwards via overridden [onTouchEvent] and returns true to receive the event chain;
 * returns false when no gesture engine is mounted, letting events pass through naturally (does not affect event routing of the M2 top UI area).
 */
class TransparentTouchLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Gesture engine; null = not connected/disconnected, touches produce no reports */
    private var gestureListener: ViewListener? = null

    /**
     * Any-touch callback (M2, DESIGN §3.4 "any touch (triangle/key bar/touch layer) resets the timer"):
     * invoked when this layer receives any touch event; MainActivity uses it to reset the key bar auto-hide timer.
     */
    var onAnyTouch: (() -> Unit)? = null

    /** Mounts the gesture engine after Bluetooth connects; pass null to unmount on disconnect (DESIGN §3.7: unmount the listener before nulling the sender) */
    fun setGestureListener(listener: ViewListener?) {
        gestureListener = listener
    }

    /**
     * Touch area alignment ([uvcpad-touch-align]): shrinks this layer to the actual display rectangle of the capture frame.
     *
     * [rect] is in coordinates relative to the parent container (rootLayout); called by MainActivity after every layout change
     * (first layout / switchMode resolution switch / rotation rebuild, see DESIGN §3.2). Zero size
     * (width/height = 0) means there is no display area to align to yet (camera not opened / view not laid out): the touch layer degrades
     * to 0×0 and no touch can land on it → no response, no HID events.
     *
     * Returns early when the value is unchanged, avoiding needless re-layout (degrades to a no-op when the layout listener fires frequently).
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
        // [uvcpad-touch-align-fix] Writing layoutParams triggers one layout pass → OnGlobalLayoutListener calls
        // syncTouchLayerBounds again; by then the values are identical and the early return above kicks in, forming a
        // harmless loop of at most 2 triggers (no infinite-loop risk).
        layoutParams = lp
    }

    /** Draws nothing (the transparent layer only receives events and draws no graphics, DESIGN §3.2) */
    override fun onDraw(canvas: Canvas) {
        // Intentionally empty: the touch layer is invisible by design
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onAnyTouch?.invoke()
        val listener = gestureListener ?: return false
        return listener.onTouch(this, event)
    }
}
