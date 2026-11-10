package com.github.ifeel.uvcpad.diag

/**
 * [uvcpad-t0-hud] Immutable snapshot rendered by the debug HUD.
 *
 * Kept outside the View so the exact text can be pinned by a pure JVM test.
 */
data class HudModel(
    val requestedW: Int,
    val requestedH: Int,
    val actualW: Int,
    val actualH: Int,
    val rotationDegrees: Int,
    val cameraState: String,
    val fps: Int,
    val hidRate: Int,
    val btState: String,
    val btName: String,
    val touchBounds: String
)

/** [uvcpad-t0-hud] Compact 4-line HUD layout (top-right, monospace, small). */
object HudText {

    fun format(m: HudModel): String = buildString {
        append("req=").append(m.requestedW).append('x').append(m.requestedH)
        append(" act=").append(m.actualW).append('x').append(m.actualH)
        append('\n')
        append("rot=").append(m.rotationDegrees).append(" cam=").append(m.cameraState)
        append(" fps=").append(m.fps)
        append('\n')
        append("hid=").append(m.hidRate).append("/s bt=").append(m.btState)
        append(" name=").append(m.btName)
        append('\n')
        append("touch=").append(m.touchBounds)
    }
}
