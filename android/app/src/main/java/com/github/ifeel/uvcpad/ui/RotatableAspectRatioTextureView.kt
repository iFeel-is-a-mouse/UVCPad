package com.github.ifeel.uvcpad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.jiangdg.ausbc.widget.IAspectRatio

/**
 * [uvcpad-rotate] Aspect-fit TextureView with an explicit display rotation (DESIGN §3.4).
 *
 * Why a copied widget instead of `AspectRatioTextureView` (AUSBC 3.6.0):
 * - the upstream class is `final` (cannot subclass) and keeps its ratio private, so a 90/270 frame cannot be
 *   transposed from the outside;
 * - AUSBC calls [setAspectRatio] with the RAW (unrotated) preview size on every camera open
 *   (`MultiCameraClient.ICamera.handleMessage(MSG_INIT)`), so a manually applied swapped ratio would be
 *   overwritten one open later;
 * - at 90/270 the rendered frame is rotated by AUSBC (`RenderManager.setRotateType` → 90° MVP rotation) but its
 *   aspect ratio is still landscape, so the display rectangle must be transposed (w/h) or the picture is stretched.
 *
 * This class keeps the exact upstream fit-inside [onMeasure] behaviour and swaps w/h inside [setAspectRatio] while
 * [rotationDegrees] is 90 or 270. AUSBC therefore continues to drive the ratio as before, just rotation-aware.
 *
 * Note: the renderer captures the view's measured size when the camera opens (no live viewport resize path in AUSBC
 * 3.6.0), so [rotationDegrees] must be set BEFORE the camera is (re)opened — MainActivity reopens the session for
 * every rotation change.
 */
class RotatableAspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), IAspectRatio {

    /** Current display rotation in degrees: 0 / 90 / 180 / 270. Must be set before the camera is (re)opened. */
    var rotationDegrees: Int = 0
        set(value) {
            val normalized = ((value % 360) + 360) % 360
            if (field == normalized) return
            field = normalized
            // Re-apply the last known preview size so the view re-measures immediately (0↔180 keeps the ratio and
            // therefore degenerates to a no-op inside setAspectRatioInternal).
            if (lastPreviewWidth > 0 && lastPreviewHeight > 0) {
                applyAspectRatio(lastPreviewWidth, lastPreviewHeight)
            }
        }

    /** Preview width/height as reported by AUSBC (raw, unrotated). 0 = not known yet. */
    private var lastPreviewWidth = 0
    private var lastPreviewHeight = 0

    /** Width / height ratio currently used for measurement; negative = not set (fills the parent, upstream behaviour). */
    private var mAspectRatio = -1.0

    // ==================== IAspectRatio (AUSBC entry points) ====================

    /** Called by AUSBC on every camera open with the raw preview size; transposed here for 90/270. */
    override fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        lastPreviewWidth = width
        lastPreviewHeight = height
        applyAspectRatio(width, height)
    }

    override fun getSurfaceWidth(): Int = measuredWidth

    override fun getSurfaceHeight(): Int = measuredHeight

    @SuppressLint("DEPRECATION")
    override fun getSurface(): Surface? {
        return try {
            Surface(surfaceTexture)
        } catch (e: Exception) {
            // SurfaceTexture not available yet (view detached / not created): upstream returns null as well
            null
        }
    }

    override fun postUITask(task: () -> Unit) {
        post { task() }
    }

    // ==================== Measurement (upstream fit-inside logic, kept verbatim) ====================

    private fun applyAspectRatio(width: Int, height: Int) {
        // Upstream posts to the UI queue (its setAspectRatio also does `post`), keeping the same ordering contract
        post {
            val swap = rotationDegrees == 90 || rotationDegrees == 270
            val w = if (swap) height else width
            val h = if (swap) width else height
            setAspectRatioInternal(w, h)
        }
    }

    private fun setAspectRatioInternal(width: Int, height: Int) {
        val ratio = width.toDouble() / height.toDouble()
        if (ratio <= 0 || ratio == mAspectRatio) return
        mAspectRatio = ratio
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = MeasureSpec.getSize(widthMeasureSpec)
        var height = MeasureSpec.getSize(heightMeasureSpec)
        val paddingX = paddingLeft + paddingRight
        val paddingY = paddingTop + paddingBottom
        width -= paddingX
        height -= paddingY
        if (width <= 0 || height <= 0) {
            // Degenerate parent: behave like a plain TextureView (upstream would divide by zero here)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val viewRatio = width.toDouble() / height.toDouble()
        val delta = mAspectRatio / viewRatio - 1.0
        var measuredWidthSpec = widthMeasureSpec
        var measuredHeightSpec = heightMeasureSpec
        if (mAspectRatio > 0 && Math.abs(delta) > 0.01) {
            if (delta > 0) {
                height = (width / mAspectRatio).toInt()
            } else {
                width = (height * mAspectRatio).toInt()
            }
            width += paddingX
            height += paddingY
            measuredWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            measuredHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        }
        super.onMeasure(measuredWidthSpec, measuredHeightSpec)
    }
}
