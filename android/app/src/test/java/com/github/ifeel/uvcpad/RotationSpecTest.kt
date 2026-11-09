package com.github.ifeel.uvcpad

import com.jiangdg.ausbc.render.env.RotateType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [uvcpad-rotate-test2] Hard-coded expectation tables for the pure rotation spec that MainActivity's ⟳ button
 * uses. The values are literals (not derived from the implementation) so a wrong mapping turns the test red:
 * - button cycle 0→1→2→3→0;
 * - display angle 0/90/180/270;
 * - AUSBC RotateType ANGLE_0/ANGLE_90/ANGLE_180/ANGLE_270.
 */
class RotationSpecTest {

    @Test
    fun nextType_cyclesThroughEveryQuarterTurnAndWraps() {
        val expected = mapOf(
            0 to 1,
            1 to 2,
            2 to 3,
            3 to 0
        )

        expected.forEach { (type, next) ->
            assertEquals("type=$type must advance to $next", next, RotationSpec.nextType(type))
        }
    }

    @Test
    fun degrees_mapsEachTypeToItsDisplayAngle() {
        val expected = mapOf(
            0 to 0,
            1 to 90,
            2 to 180,
            3 to 270
        )

        expected.forEach { (type, degrees) ->
            assertEquals("type=$type must display at $degrees°", degrees, RotationSpec.degrees(type))
        }
    }

    @Test
    fun rotateTypeOf_mapsEachTypeToTheAusbcAngle() {
        val expected = mapOf(
            0 to RotateType.ANGLE_0,
            1 to RotateType.ANGLE_90,
            2 to RotateType.ANGLE_180,
            3 to RotateType.ANGLE_270
        )

        expected.forEach { (type, rotateType) ->
            assertEquals("type=$type must request $rotateType", rotateType, RotationSpec.rotateTypeOf(type))
        }
    }
}
