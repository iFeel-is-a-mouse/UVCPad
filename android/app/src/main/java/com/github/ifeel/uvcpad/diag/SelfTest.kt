package com.github.ifeel.uvcpad.diag

/**
 * [uvcpad-t0-selftest] Pure interpretation of the 12 pre-flight checks.
 *
 * The Activity only gathers raw platform facts into [SelfTestSnapshot]; this object maps
 * them onto ordered ✅/❌ items with an actionable hint for every failing row. Keeping the
 * mapping pure makes the expected status table unit-testable without a device.
 */
data class SelfTestSnapshot(
    val usbHostSupported: Boolean,
    val captureCardAttached: Boolean,
    val cameraPermissionGranted: Boolean,
    val usbPermissionGranted: Boolean,
    val cameraOpened: Boolean,
    val btAdapterEnabled: Boolean,
    val btConnectGranted: Boolean,
    val btScanGranted: Boolean,
    val btAdvertiseGranted: Boolean,
    val hidProxyAvailable: Boolean,
    val hidAppRegistered: Boolean,
    val pairedDeviceAvailable: Boolean
)

data class SelfTestItem(
    val id: String,
    val label: String,
    val ok: Boolean,
    val hint: String
)

object SelfTest {

    fun evaluate(s: SelfTestSnapshot): List<SelfTestItem> = listOf(
        item("usb_host", "USB host support", s.usbHostSupported,
            "This device/OTG port does not expose USB host mode"),
        item("capture_card", "Capture card attached", s.captureCardAttached,
            "Plug the MS2130 capture card into the OTG port"),
        item("camera_permission", "Camera permission", s.cameraPermissionGranted,
            "Grant Camera permission in system settings"),
        item("usb_permission", "USB device authorized", s.usbPermissionGranted,
            "Tap Allow / Always allow in the USB permission dialog"),
        item("camera_opened", "Camera opened", s.cameraOpened,
            "Replug the card or restart the app"),
        item("bt_adapter", "Bluetooth adapter enabled", s.btAdapterEnabled,
            "Turn Bluetooth on"),
        item("bt_connect", "BLUETOOTH_CONNECT", s.btConnectGranted,
            "Grant Nearby devices permission"),
        item("bt_scan", "BLUETOOTH_SCAN", s.btScanGranted,
            "Grant Nearby devices permission"),
        item("bt_advertise", "BLUETOOTH_ADVERTISE", s.btAdvertiseGranted,
            "Grant Nearby devices permission (Make Discoverable)"),
        item("hid_proxy", "HID profile proxy", s.hidProxyAvailable,
            "Tap the Bluetooth button to re-initialize the HID profile"),
        item("hid_registered", "HID app registered", s.hidAppRegistered,
            "Tap the Bluetooth button to retry registerApp"),
        item("paired_device", "Paired device present/connected", s.pairedDeviceAvailable,
            "Pair the PC in system Bluetooth settings")
    )

    private fun item(id: String, label: String, ok: Boolean, hint: String) =
        SelfTestItem(id, label, ok, if (ok) "" else hint)

    /** Renders one line per check: `✅ label` or `❌ label — hint`. */
    fun format(items: List<SelfTestItem>): String = buildString {
        items.forEachIndexed { index, item ->
            if (index > 0) append('\n')
            append(if (item.ok) "✅ " else "❌ ")
            append(item.label)
            if (!item.ok) {
                append(" — ")
                append(item.hint)
            }
        }
    }
}
