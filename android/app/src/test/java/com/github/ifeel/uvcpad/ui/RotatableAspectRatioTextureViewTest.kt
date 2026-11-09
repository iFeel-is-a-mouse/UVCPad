package com.github.ifeel.uvcpad.ui

import android.app.Activity
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Aspect-ratio state of [RotatableAspectRatioTextureView]: the 90°/270° display must transpose w/h,
 * 0°/180° must keep the raw preview ratio (DESIGN §3.4).
 *
 * The view measures itself fit-inside into a 1000×1000 parent, so the measured size directly exposes the
 * ratio actually used: preview 1600×1200 → 1000×750 at 0°/180°, 750×1000 at 90°/270°.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RotatableAspectRatioTextureViewTest {

    private lateinit var activity: Activity
    private lateinit var view: RotatableAspectRatioTextureView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = RotatableAspectRatioTextureView(ApplicationProvider.getApplicationContext())
        // Attached so the view's posted ratio application runs on the main looper
        activity.setContentView(view)
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun applyRatio(width: Int, height: Int) {
        view.setAspectRatio(width, height)
        idle()
    }

    /** Measures into a 1000×1000 parent and returns measuredWidth to measuredHeight. */
    private fun measureInSquareParent(): Pair<Int, Int> {
        view.measure(
            MeasureSpec.makeMeasureSpec(1000, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(1000, MeasureSpec.AT_MOST)
        )
        return view.measuredWidth to view.measuredHeight
    }

    @Test
    fun rotation0_keepsRawPreviewRatio() {
        view.rotationDegrees = 0
        applyRatio(1600, 1200)

        assertEquals(1000 to 750, measureInSquareParent())
    }

    @Test
    fun rotation90_transposesWidthAndHeight() {
        view.rotationDegrees = 90
        applyRatio(1600, 1200)

        // 1200:1600 = 0.75 → 750×1000 (instead of 1000×750)
        assertEquals(750 to 1000, measureInSquareParent())
    }

    @Test
    fun rotation180_keepsRawPreviewRatio() {
        view.rotationDegrees = 180
        applyRatio(1600, 1200)

        assertEquals(1000 to 750, measureInSquareParent())
    }

    @Test
    fun rotation270_transposesWidthAndHeight() {
        view.rotationDegrees = 270
        applyRatio(1600, 1200)

        assertEquals(750 to 1000, measureInSquareParent())
    }

    @Test
    fun rotationChange_reappliesRatioWithoutNewPreviewSize() {
        view.rotationDegrees = 0
        applyRatio(1600, 1200)
        assertEquals(1000 to 750, measureInSquareParent())

        // 0° → 90°: last known preview size is re-applied with the swapped ratio
        view.rotationDegrees = 90
        idle()
        assertEquals(750 to 1000, measureInSquareParent())

        // 90° → 180°: swap goes away again
        view.rotationDegrees = 180
        idle()
        assertEquals(1000 to 750, measureInSquareParent())

        // 180° → 270°: swap returns
        view.rotationDegrees = 270
        idle()
        assertEquals(750 to 1000, measureInSquareParent())
    }

    @Test
    fun rotationDegrees_areNormalised() {
        view.rotationDegrees = 450
        assertEquals(90, view.rotationDegrees)

        view.rotationDegrees = -90
        assertEquals(270, view.rotationDegrees)

        view.rotationDegrees = 720
        assertEquals(0, view.rotationDegrees)
    }

    @Test
    fun fullHdPreview_transposesToPortraitRectangle() {
        view.rotationDegrees = 90
        applyRatio(1920, 1080)

        // 1080:1920 = 0.5625 → height 1000, width 562
        assertEquals(562 to 1000, measureInSquareParent())
    }

    @Test
    fun invalidPreviewSize_isIgnored() {
        view.rotationDegrees = 0
        applyRatio(0, 1200)
        applyRatio(1600, -1)

        // No ratio was ever applied → plain TextureView fills the parent (upstream behaviour)
        assertEquals(1000 to 1000, measureInSquareParent())
    }

    @Test
    fun viewFillsParentWhenNoRatioIsKnown() {
        assertEquals(1000 to 1000, measureInSquareParent())
    }

    @Test
    fun degenerateParent_fallsBackToPlainMeasurement() {
        view.rotationDegrees = 90
        applyRatio(1600, 1200)

        view.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
        )
        assertEquals(0, view.measuredWidth)
        assertEquals(0, view.measuredHeight)
    }

    private fun assertMeasuredSize(expected: Pair<Int, Int>) {
        assertEquals(expected, measureInSquareParent())
    }

    @Test
    fun getSurfaceSize_reportsMeasuredSize() {
        view.rotationDegrees = 90
        applyRatio(1600, 1200)
        assertMeasuredSize(750 to 1000)

        assertEquals(750, view.getSurfaceWidth())
        assertEquals(1000, view.getSurfaceHeight())
    }

    @Test
    fun viewIsLaidOutAtItsRotatedMeasuredSize() {
        view.rotationDegrees = 90
        applyRatio(1600, 1200)

        val (width, height) = measureInSquareParent()
        view.layout(0, 0, width, height)

        // Real layout state: the view is laid out at the measured 750×1000 (rotated) rectangle.
        assertEquals(true, view.isLaidOut)
        assertEquals(750, width)
        assertEquals(1000, height)
        assertEquals(width, view.width)
        assertEquals(height, view.height)
    }
}
