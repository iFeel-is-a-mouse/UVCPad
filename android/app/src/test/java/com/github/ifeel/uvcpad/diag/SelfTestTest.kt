package com.github.ifeel.uvcpad.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [uvcpad-t0-selftest] Fake platform states → expected ✅/❌ table. */
class SelfTestTest {

    private fun snapshot(
        usbHost: Boolean = true,
        card: Boolean = true,
        cameraPerm: Boolean = true,
        usbPerm: Boolean = true,
        cameraOpen: Boolean = true,
        adapter: Boolean = true,
        connect: Boolean = true,
        scan: Boolean = true,
        advertise: Boolean = true,
        proxy: Boolean = true,
        registered: Boolean = true,
        paired: Boolean = true
    ) = SelfTestSnapshot(
        usbHost, card, cameraPerm, usbPerm, cameraOpen,
        adapter, connect, scan, advertise, proxy, registered, paired
    )

    @Test
    fun allGoodProducesTwelveGreenRows() {
        val items = SelfTest.evaluate(snapshot())
        assertEquals(12, items.size)
        assertTrue(items.all { it.ok })
        assertEquals(
            listOf(
                "usb_host", "capture_card", "camera_permission", "usb_permission",
                "camera_opened", "bt_adapter", "bt_connect", "bt_scan",
                "bt_advertise", "hid_proxy", "hid_registered", "paired_device"
            ),
            items.map { it.id }
        )
    }

    @Test
    fun allBadProducesTwelveRedRowsWithHints() {
        val items = SelfTest.evaluate(
            snapshot(
                usbHost = false, card = false, cameraPerm = false, usbPerm = false,
                cameraOpen = false, adapter = false, connect = false, scan = false,
                advertise = false, proxy = false, registered = false, paired = false
            )
        )
        assertEquals(12, items.size)
        assertTrue(items.none { it.ok })
        assertTrue(items.all { it.hint.isNotBlank() })
    }

    @Test
    fun mixedStatesMapToExpectedStatuses() {
        val items = SelfTest.evaluate(
            snapshot(
                card = false, cameraOpen = false, advertise = false, registered = false
            )
        )
        assertEquals(
            listOf(true, false, true, true, false, true, true, true, false, true, false, true),
            items.map { it.ok }
        )
        assertEquals("Plug the MS2130 capture card into the OTG port", items[1].hint)
    }

    @Test
    fun formatShowsCheckmarksAndHints() {
        val items = SelfTest.evaluate(snapshot(card = false))
        val text = SelfTest.format(items)
        val lines = text.split('\n')
        assertEquals(12, lines.size)
        assertEquals("✅ USB host support", lines[0])
        assertEquals("❌ Capture card attached — Plug the MS2130 capture card into the OTG port", lines[1])
    }
}
