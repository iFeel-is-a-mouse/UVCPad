package com.github.ifeel.uvcpad.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/** [uvcpad-t0-hud] Exact HUD text layout (four compact k=v lines). */
class HudTextTest {

    private fun model(
        requestedW: Int = 1920,
        requestedH: Int = 1080,
        actualW: Int = 1600,
        actualH: Int = 1200,
        rotation: Int = 90,
        camera: String = "OPENED",
        fps: Int = 30,
        hid: Int = 42,
        btState: String = "CONNECTED",
        btName: String = "PC-01",
        bounds: String = "0,0 1600x1200"
    ) = HudModel(
        requestedW, requestedH, actualW, actualH,
        rotation, camera, fps, hid, btState, btName, bounds
    )

    @Test
    fun formatsAllDiagnosticFields() {
        val expected =
            "req=1920x1080 act=1600x1200\n" +
                "rot=90 cam=OPENED fps=30\n" +
                "hid=42/s bt=CONNECTED name=PC-01\n" +
                "touch=0,0 1600x1200"
        assertEquals(expected, HudText.format(model()))
    }

    @Test
    fun formatsIdleStateWithDashName() {
        val expected =
            "req=1872x1404 act=1872x1404\n" +
                "rot=0 cam=IDLE fps=0\n" +
                "hid=0/s bt=OFF name=-\n" +
                "touch=0,0 0x0"
        assertEquals(
            expected,
            HudText.format(
                model(
                    requestedW = 1872, requestedH = 1404,
                    actualW = 1872, actualH = 1404,
                    rotation = 0, camera = "IDLE", fps = 0, hid = 0,
                    btState = "OFF", btName = "-", bounds = "0,0 0x0"
                )
            )
        )
    }
}
