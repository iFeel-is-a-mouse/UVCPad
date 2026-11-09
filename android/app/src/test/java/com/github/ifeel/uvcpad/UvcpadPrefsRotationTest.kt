package com.github.ifeel.uvcpad

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rotation preference state: persistence, normalisation of illegal persisted values and the button label
 * angle derived from the pref (the same `rotationType * 90` the rotate button displays).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UvcpadPrefsRotationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seedRotationType(raw: Int) {
        context.getSharedPreferences("uvcpad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt(UvcpadPrefs.KEY_ROTATION_TYPE, raw)
            .commit()
    }

    private fun readRotationType(): Int = UvcpadPrefs(context).rotationType

    @Test
    fun missingValue_defaultsToZeroDegrees() {
        assertEquals(0, readRotationType())
    }

    @Test
    fun validValues_roundTripAndPersistAcrossInstances() {
        for (type in 0..3) {
            UvcpadPrefs(context).rotationType = type
            assertEquals("fresh instance must see the persisted value", type, readRotationType())
        }
    }

    @Test
    fun negativeValue_isNormalisedOntoTheEnum() {
        seedRotationType(-1)
        assertEquals(3, readRotationType())

        seedRotationType(-5)
        assertEquals(3, readRotationType())

        seedRotationType(-8)
        assertEquals(0, readRotationType())
    }

    @Test
    fun outOfRangePositiveValue_isNormalisedOntoTheEnum() {
        seedRotationType(7)
        assertEquals(3, readRotationType())

        seedRotationType(100)
        assertEquals(0, readRotationType())

        seedRotationType(5)
        assertEquals(1, readRotationType())
    }

    @Test
    fun everyReadValueIsWithinTheEnumRange() {
        for (raw in -100..100) {
            seedRotationType(raw)
            val normalized = readRotationType()
            assertEquals("raw=$raw must normalise into 0..3", true, normalized in 0..3)
            assertEquals(
                "raw=$raw must equal the companion helper",
                UvcpadPrefs.normalizeRotationType(raw),
                normalized
            )
        }
    }

    @Test
    fun buttonLabelAngle_matchesThePrefForEveryValue() {
        // Mirrors MainActivity.updateRotateButton(): 0° shows "⟳", otherwise "⟳<degrees>°" with
        // degrees = normalised rotationType * 90.
        val cases = listOf(
            -1 to "⟳270°",
            -5 to "⟳270°",
            0 to "⟳",
            1 to "⟳90°",
            2 to "⟳180°",
            3 to "⟳270°",
            4 to "⟳",
            7 to "⟳270°",
            100 to "⟳"
        )
        for ((raw, expectedLabel) in cases) {
            seedRotationType(raw)
            val degrees = readRotationType() * 90
            val label = if (degrees == 0) {
                context.getString(R.string.keybar_rotate)
            } else {
                context.getString(R.string.keybar_rotate_angle, degrees)
            }
            assertEquals("raw=$raw → ${degrees}°", expectedLabel, label)
        }
    }

    @Test
    fun labelResources_containTheExpectedGlyphAndAngle() {
        assertEquals("⟳", context.getString(R.string.keybar_rotate))
        assertEquals("⟳90°", context.getString(R.string.keybar_rotate_angle, 90))
        assertEquals("⟳180°", context.getString(R.string.keybar_rotate_angle, 180))
        assertEquals("⟳270°", context.getString(R.string.keybar_rotate_angle, 270))
    }
}
