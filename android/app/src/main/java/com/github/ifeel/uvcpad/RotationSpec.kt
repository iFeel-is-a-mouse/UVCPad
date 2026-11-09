package com.github.ifeel.uvcpad

import com.jiangdg.ausbc.render.env.RotateType

/**
 * [uvcpad-rotate-test2] Pure rotation logic behind the ⟳ button, extracted from [MainActivity] so it can be
 * unit-tested without an Activity/camera session.
 *
 * Behaviour is byte-for-byte the previous inline code:
 * - the button cycles `(type + 1) % 4` (0→1→2→3→0);
 * - the display angle is `normalise(type) * 90`;
 * - AUSBC's render rotation is ANGLE_0/90/180/270 for type 0/1/2/3.
 *
 * `internal` (not private) so the unit tests in the same module can pin the hard-coded expectation tables.
 */
internal object RotationSpec {

    /** Number of quarter-turn positions (0/90/180/270). */
    const val TYPE_COUNT = 4

    /** ⟳ button cycle: 0→1→2→3→0. */
    fun nextType(type: Int): Int = (type + 1) % TYPE_COUNT

    /** Maps an arbitrary persisted value onto 0..3 (corrupt/legacy prefs may hold -1, 7, 100 …). */
    fun normalize(type: Int): Int = ((type % TYPE_COUNT) + TYPE_COUNT) % TYPE_COUNT

    /** prefs.rotationType (0..3) → display angle in degrees (0/90/180/270). */
    fun degrees(type: Int): Int = normalize(type) * 90

    /** prefs.rotationType (0..3) → AUSBC render rotation. */
    fun rotateTypeOf(type: Int): RotateType = when (normalize(type)) {
        1 -> RotateType.ANGLE_90
        2 -> RotateType.ANGLE_180
        3 -> RotateType.ANGLE_270
        else -> RotateType.ANGLE_0
    }
}
