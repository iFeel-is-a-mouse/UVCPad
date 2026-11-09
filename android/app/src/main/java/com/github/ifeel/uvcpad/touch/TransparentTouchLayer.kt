package com.github.ifeel.uvcpad.touch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
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
     * [uvcpad-rotate] Current display rotation in degrees (0/90/180/270), kept in sync with the camera frame rotation.
     * Non-zero makes [onTouchEvent] rotate the touch coordinates back into the unrotated frame before the gesture
     * engine sees them, so swipe directions still match what the user sees on screen.
     */
    var displayRotationDegrees: Int = 0

    /** Reusable matrix for the per-event coordinate rotation (avoids an allocation per MOVE) */
    private val rotateMatrix = Matrix()

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
        val degrees = ((displayRotationDegrees % 360) + 360) % 360
        if (degrees == 0) {
            return listener.onTouch(this, event)
        }
        // [uvcpad-rotate] Rotate the event coordinates back into the unrotated frame (DESIGN §3.4).
        // AUSBC's RotateType.ANGLE_90 is a +90° rotation applied in clip space (y-up), which appears as a 90°
        // COUNTER-clockwise turn on screen. Recovering the original point is therefore the inverse turn, i.e.
        // Matrix.setRotate(+degrees) (Android angles are clockwise because y grows downwards):
        //   90°  → setRotate(+90): displayed delta (dx,dy) becomes (-dy,dx)  (displayed right = original down)
        //   180° → setRotate(+180): deltas negate
        //   270° → setRotate(-90): displayed delta becomes (dy,-dx)
        // MotionEvent.transform applies the matrix to every pointer (including historical samples), which keeps
        // multi-finger scroll/pinch deltas consistent automatically. The original event is untouched → Android's
        // ACTION_DOWN hit-test (event ownership / dispatch target) is unaffected; only the copy handed to the
        // gesture engine carries rotated coordinates.
        val rotated = MotionEvent.obtain(event)
        rotateMatrix.setRotate(degrees.toFloat(), width / 2f, height / 2f)
        rotated.transform(rotateMatrix)
        return try {
            listener.onTouch(this, rotated)
        } finally {
            rotated.recycle()
        }
    }
}
