package com.github.ifeel.uvcpad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.ifeel.uvcpad.bt.BluetoothController
import com.github.ifeel.uvcpad.bt.SpeedLevel
import com.github.ifeel.uvcpad.bt.listeners.ViewListener
import com.github.ifeel.uvcpad.bt.senders.RelativeMouseSender
import com.github.ifeel.uvcpad.touch.TransparentTouchLayer
import com.github.ifeel.uvcpad.ui.DropTriangleView
import com.github.ifeel.uvcpad.ui.KeyBarController
import com.github.ifeel.uvcpad.ui.KeyBarPanel
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.MediaStoreUtils
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * uvcpad main screen: single Activity hosting both chains (DESIGN §2/§3).
 *
 * 1. Display chain (from hdmi2mp, kept verbatim): extends AUSBC CameraActivity; after a USB
 *    device is plugged in, it automatically requests permission and opens the UVC camera;
 *    OPENGL rendering on AspectRatioTextureView, 1920×1080 / 1872×1404 presets, screenshot
 *    pipeline kept (key-bar entry, M2).
 * 2. Touch chain (from KeysJoy, mouse-only): Bluetooth HID registered as a mouse
 *    (BluetoothController, DESIGN §4.2); once connected, the full-screen TransparentTouchLayer
 *    is wired to the ViewListener gesture engine; on disconnect the listener is detached
 *    before anything else (DESIGN §3.7).
 * 3. M2 interaction entry (DESIGN §3.3/§3.4): drop triangle (sole persistent UI) toggles the
 *    auto-hiding key bar (speed / bluetooth+device switcher / auto-pair / resolution /
 *    screenshot / exit, no keyboard items); triangle & key-bar areas consume their own touch
 *    events so they never produce mouse reports (M2 acceptance key).
 *
 * M1 adaptations vs hdmi2mp MainActivity (all documented in journey.md):
 * - adaptation ① toolbar buttons removed (btnMode1080p/btnMode4by3/btnCapture/btnExit/topOverlay
 *   and its auto-hide timer): M1 has no clickable UI on the touchpad (M2 re-introduces the
 *   key bar behind the drop triangle); switchMode lost its button/highlight params for the
 *   same reason (M2 key-bar buttons will call the parameterless switchMode),
 * - statusText removed from the M1 layout (DESIGN §3.1 has no standalone status line): camera
 *   OPENED state is surfaced via toast, errors via the errorText area,
 * - serial runtime-permission chain extended per DESIGN §1.4: CAMERA → BLUETOOTH_CONNECT/SCAN
 *   (S+) → ACCESS_COARSE_LOCATION (≤30) → WRITE_EXTERNAL_STORAGE (23–28),
 * - BT lifecycle follows the KeysJoy SelectDeviceActivity pattern (onStart init +
 *   getSender/getDisconnector, onResume force re-init after background, DESIGN §3.7).
 */
class MainActivity : CameraActivity() {

    companion object {
        private const val MODE_1080P_W = 1920
        private const val MODE_1080P_H = 1080
        private const val MODE_4BY3_W = 1872
        private const val MODE_4BY3_H = 1404
        private const val TAG = "MainActivity"
        // State save keys (keep the selected resolution mode across config changes / process recreation)
        private const val KEY_MODE_W = "key_mode_w"
        private const val KEY_MODE_H = "key_mode_h"
        // [uvcpad-toast-singleton] Global Toast singleton: cancel the old message before showing a new one → latest wins, no queue buildup
        private var sToast: Toast? = null
    }

    // [uvcpad-default-4by3-mem] Default initial 4:3 (1872×1404). onCreate overrides it with the SharedPreferences
    // remembered value (first launch with no memory = stays at the 4:3 default; with a remembered choice, the previous size is restored).
    private var currentModeW = MODE_4BY3_W
    private var currentModeH = MODE_4BY3_H

    /**
     * Resolution-switch request target (AUSBC 3.6.0 updateResolution is asynchronous: closeCamera + reopens the camera 1s later,
     * internally auto-negotiating the closest supported size; it does not throw). The actual negotiated result is read back in
     * onCameraState(OPENED) via getCurrentPreviewSize() — pending records the requested target so the OPENED callback can tell whether a fallback occurred.
     * 0 means no switch request in progress.
     */
    private var pendingModeW = 0
    private var pendingModeH = 0

    private lateinit var errorText: TextView
    private lateinit var touchLayer: TransparentTouchLayer
    private lateinit var rootLayout: View
    private lateinit var cameraViewContainer: ViewGroup

    // ============ M2: drop triangle + key bar (DESIGN §3.3/§3.4) ============
    private lateinit var dropTriangle: DropTriangleView
    private lateinit var keyBar: KeyBarPanel
    private lateinit var keyBarController: KeyBarController
    private lateinit var btnSpeed: TextView
    private lateinit var btnBt: TextView
    private lateinit var btnAutoPair: TextView
    private lateinit var btnMode: TextView
    private lateinit var btnCapture: TextView
    private lateinit var btnExit: TextView

    /**
     * [uvcpad-touch-align] Layout sync: on every layout change (first layout / switchMode resolution switch / rotation rebuild)
     * shrink the touch layer to the actual display rectangle of the capture frame (= AspectRatioTextureView's layout bounds, DESIGN §3.2).
     */
    private val touchAlignLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        syncTouchLayerBounds()
    }

    private lateinit var prefs: UvcpadPrefs

    /** Speed preset applied to the gesture engine at connection time (DESIGN §3.6: level 4 = 1.0f default) */
    private var currentSpeedLevel: SpeedLevel = SpeedLevel.DEFAULT

    /** Current gesture-engine reference: the speed button updates mouseSpeed/scrollSpeed in real time (DESIGN §3.4); created on connect, nulled on disconnect */
    private var viewListener: ViewListener? = null

    // Whether the USB permission guidance hint is currently shown: avoids spamming toasts on
    // every attach intent; cleared as soon as the user grants permission (from hdmi2mp verbatim)
    private var usbHintActive = false

    /** [uvcpad-fix-p2] C1: USB-permission-hint timeout reset timer (expires 30s after the dialog is ignored, re-prompts on the next attach) */
    private val usbHintHandler = Handler(Looper.getMainLooper())

    // Track whether activity was in background (for BT lifecycle re-init, KeysJoy pattern)
    private var wasInBackground = false

    // ==================== Serial runtime permissions (DESIGN §1.4) ====================
    // Chain: CAMERA → BLUETOOTH_CONNECT/BLUETOOTH_SCAN (S+, merged) → ACCESS_COARSE_LOCATION
    // (≤30) → WRITE_EXTERNAL_STORAGE (23–28). Each launcher continues the chain in its result
    // callback so the system permission dialogs never stack (hdmi2mp L2 pattern extended).

    // Set when the serial chain actually launches a permission dialog. The chain tail uses it
    // to run the first-launch BT init that onStart had to skip while dialogs were still up
    // (P2 fix, uvcpad-P2-fix); stays false when every permission was already granted.
    private var permissionDialogShown = false

    // API 23~28 need the storage permission to import screenshots into the system gallery (29+ uses MediaStore, no permission needed)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            val msg = getString(R.string.storage_permission_denied)
            toast(msg)
            showError(msg)
        }
        // Chain tail on API 23–28: last dialog is done — run the first-launch BT init that
        // onStart skipped (P2 fix; no-op unless the chain actually showed dialogs)
        maybeInitBluetoothAfterFirstLaunch()
    }

    // API≤30 needs location for Bluetooth discovery (S+ exempted: BLUETOOTH_SCAN declares neverForLocation)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            // Only affects discovering new devices; already-paired devices still work.
            // Non-blocking: continue the chain (from KeysJoy splash flow, informational only)
            toast(getString(R.string.location_permission_denied))
        }
        requestStoragePermissionIfNeeded()
    }

    // Android 12+ (S): BLUETOOTH_CONNECT + BLUETOOTH_SCAN requested together (KeysJoy splash mode)
    private val btPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = result[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                result[Manifest.permission.BLUETOOTH_SCAN] == true
            if (!granted) {
                val msg = getString(R.string.bt_permission_denied)
                toast(msg)
                showError(msg)
            }
        }
        requestLocationPermissionIfNeeded()
    }

    // Camera runtime permission: merged declaration from the library; required to open the MS2130 UVC.
    // The next permission is requested only after the camera result callback, so the two system
    // permission dialogs appear serially and never stack (hdmi2mp L2 fix)
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            val msg = getString(R.string.camera_permission_denied)
            toast(msg)
            showError(msg)
        }
        requestBtPermissionsIfNeeded()
    }

    // ==================== Lifecycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the active Activity reference for the global crash self-capture (lets CrashHandler show the full-screen crash dialog)
        CrashHandler.setActiveActivity(this)
        prefs = UvcpadPrefs(this)
        // [uvcpad-resolution-mode] Startup restore: derive the preset request size from prefs.resolutionMode (the user-selected mode enum)
        // (4:3 → 1872×1404, 16:9 → 1920×1080). What is remembered is the user's choice, not the
        // hardware fallback result — memory stays unchanged after swapping hardware, and the next launch still requests the preset per the user's mode.
        // savedInstanceState (config changes like rotation) holds the currently displayed actually negotiated size and is restored first
        // to keep the display continuous after rotation; on process-level restart savedInstanceState is null → go through the mode preset.
        val modePresetW = if (prefs.resolutionMode == UvcpadPrefs.MODE_16_9) MODE_1080P_W else MODE_4BY3_W
        val modePresetH = if (prefs.resolutionMode == UvcpadPrefs.MODE_16_9) MODE_1080P_H else MODE_4BY3_H
        if (savedInstanceState != null) {
            currentModeW = savedInstanceState.getInt(KEY_MODE_W, modePresetW)
            currentModeH = savedInstanceState.getInt(KEY_MODE_H, modePresetH)
        } else {
            currentModeW = modePresetW
            currentModeH = modePresetH
        }
        // The initial request shares the same async read-back logic as switchMode (v0.2.3): record the request target as pending,
        // then OPENED reads back the actually negotiated size — if the first 4:3 request falls back because EDID does not support it, it is likewise shown truthfully and notified.
        pendingModeW = currentModeW
        pendingModeH = currentModeH
        currentSpeedLevel = SpeedLevel.forLevel(prefs.speedLevel)
        // Auto-pair toggle (KeysJoy pattern): sync flag + start the reconnect loop when enabled
        BluetoothController.autoPairFlag = prefs.autoPair
        // [uvcpad-fix-p1] New session resets the manual-disconnect marker (prevents a leftover manual disconnect from blocking this session's auto-connect)
        BluetoothController.manualDisconnectFlag = false
        if (prefs.autoPair) {
            BluetoothController.startAutoReconnect()
        }
        // [uvcpad-last-device] Most-recent-device memory (multi-device auto-connect selection): inject the prefs remembered value at startup;
        // every successful connection writes back to prefs via lastDeviceConnectedListener (manual switch success counts too →
        // the next auto-connect prefers the new device, matching the "most recent connection" semantics)
        BluetoothController.lastDeviceAddress = prefs.lastDeviceAddress
        BluetoothController.lastDeviceConnectedListener = { device ->
            prefs.lastDeviceAddress = device.address
        }
        // [uvcpad-consistency-p2] When the remembered address is invalidated (unbonded/re-paired, decided by resolveAutoConnectTarget),
        // clear the persisted prefs memory in sync to avoid re-injecting a dead device address on the next launch
        BluetoothController.lastDeviceAddressRemovedListener = {
            prefs.lastDeviceAddress = null
        }
        // Screen always-on (default ON, same behavior as hdmi2mp)
        if (prefs.screenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        bindViews()
        // Serial permission requests: camera first (required for UVC); BT (S+), location (≤30)
        // and storage (23~28) follow inside the result callbacks so the dialogs never stack
        requestPermissionsSequentially()
        // When launched by a USB attach intent, check the USB authorization state and prompt (AUSBC shows the system authorization dialog automatically)
        checkUsbPermissionHint(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Capture card plugged in while the app is already in the foreground: check the USB authorization state and prompt as well
        checkUsbPermissionHint(intent)
    }

    override fun onStart() {
        super.onStart()
        initBluetooth()
    }

    /**
     * KeysJoy onStart init path: init the HID profile and register the connect/disconnect
     * wiring. Guarded by permissions + adapter state (the serial permission flow may still
     * be showing dialogs; onResume re-init covers the late path). Shared by the permission
     * chain tail (P2 fix) so the first-launch grant re-runs exactly the same init.
     *
     * @return true if the init chain was actually requested (both guards passed);
     * false if skipped early (permission missing / BT disabled). [uvcpad-l1]
     */
    private fun initBluetooth(): Boolean {
        if (!checkBluetoothPermissions()) return false
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) {
            toast(getString(R.string.bt_disabled_hint))
            return false
        }
        BluetoothController.init(this)
        // Register sender callback — fires immediately if already connected,
        // or later when a device connects (on binder thread → runOnUiThread)
        BluetoothController.getSender { hidDevice, host ->
            runOnUiThread { setupTouchLayer(hidDevice, host) }
        }
        // Register disconnector callback
        BluetoothController.getDisconnector {
            runOnUiThread { teardownTouchLayer() }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        CrashHandler.setActiveActivity(this)
        // DESIGN §3.7 (KeysJoy pattern): force full re-init when returning from background —
        // btHid may be stale after app switch. M1 fix: merged into a single guarded path so
        // init() (async getProfileProxy) is requested at most once per onResume.
        if (wasInBackground) {
            wasInBackground = false
            BluetoothController.btHid = null
            BluetoothController.hostDevice = null
        }
        if (BluetoothController.btHid == null) {
            BluetoothController.init(this)
        } else if (BluetoothController.hostDevice == null) {
            // btHid alive but no active connection: reset and re-init
            BluetoothController.btHid = null
            BluetoothController.hostDevice = null
            BluetoothController.init(this)
        }
        // Status callback: HID registration / discoverability / connect / disconnect hints.
        // M1 has no status text view (key bar lands in M2), so status goes to toasts
        BluetoothController.statusListener = { msg ->
            runOnUiThread { toast(msg) }
        }
    }

    override fun onStop() {
        super.onStop()
        wasInBackground = true
        // [uvcpad-consistency-p3] Drop the in-flight switch intent when going to background: after rotation/rebuild/return-to-foreground a leftover
        // targetSwitchDevice would trigger a connect to the old device in the onConnectionStateChanged branch (switchTo then background within 3s scenario)
        BluetoothController.targetSwitchDevice = null
    }

    override fun onDestroy() {
        // DESIGN §3.7: stop auto-reconnect + clear the status listener + drop the crash-dialog ref
        BluetoothController.stopAutoReconnect()
        // [uvcpad-fix-p1] Clear all singleton callbacks: deviceListener/disconnectListener/statusListener nulled together,
        // preventing the singleton from holding lambdas that reference the destroyed Activity (leak)
        BluetoothController.clearListeners()
        // [uvcpad-last-device] Clear the connection-success callback: avoids the singleton holding a reference to the destroyed Activity
        BluetoothController.lastDeviceConnectedListener = null
        // [uvcpad-consistency-p2] Clear the memory-invalidation callback (same reason, prevents reference leaks)
        BluetoothController.lastDeviceAddressRemovedListener = null
        // [uvcpad-fix-p2] C1: clear the USB-permission-hint timeout reset task
        usbHintHandler.removeCallbacksAndMessages(null)
        // M2: clear the key bar auto-hide timer and animations (hdmi2mp: removeCallbacksAndMessages pattern)
        if (::keyBarController.isInitialized) {
            keyBarController.destroy()
        }
        CrashHandler.setActiveActivity(null)
        // [uvcpad-touch-align]: remove the layout-sync listener so it never fires on a dead Activity
        if (::cameraViewContainer.isInitialized) {
            cameraViewContainer.viewTreeObserver
                .removeOnGlobalLayoutListener(touchAlignLayoutListener)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MODE_W, currentModeW)
        outState.putInt(KEY_MODE_H, currentModeH)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ============ AUSBC CameraActivity abstract methods (hdmi2mp verbatim) ============

    override fun getRootView(layoutInflater: LayoutInflater): View? {
        return layoutInflater.inflate(R.layout.activity_main, null)
    }

    override fun getCameraView(): IAspectRatio? {
        // Create the render view at runtime; CameraActivity adds it to the container automatically
        return AspectRatioTextureView(this)
    }

    override fun getCameraViewContainer(): ViewGroup? {
        return findViewById(R.id.cameraViewContainer)
    }

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            // Frame rate note: AUSBC 3.6.0 CameraRequest.Builder exposes no API to set the
            // frame rate explicitly (no setFps/setPreviewFps etc.), so 60fps is not requested
            // explicitly; rely on UVC default negotiation: the MS2130 outputs up to
            // 1920×1080@60 (MJPEG) per its device descriptor.
            // [uvcpad-default-4by3-mem] The request size follows currentModeW/H (the remembered value restored in onCreate;
            // first launch = 4:3 1872×1404), no longer hardcoded to 1080p.
            .setPreviewWidth(currentModeW)
            .setPreviewHeight(currentModeH)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            // No audio needed
            .setAudioSource(CameraRequest.AudioSource.NONE)
            // MJPEG: high frame rate, low latency
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            // Required for screenshots: NV21 frames are still delivered and queued in OPENGL mode
            .setCaptureRawImage(true)
            .setRawPreviewData(true)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        runOnUiThread {
            // Fallback try-catch inside the callback: exceptions thrown from AUSBC callbacks are
            // also surfaced on screen instead of crashing silently (hdmi2mp verbatim)
            try {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        val actual = getCurrentPreviewSize()
                        if (actual != null) {
                            // Use the camera's actually negotiated size as truth: AUSBC falls back to the closest supported size
                            // (e.g. 4:3 bucket 1872×1404 falls back to 1920×1080 on devices without that resolution),
                            // and the UI must follow the real negotiated result, otherwise the button shows a fake 4:3 while the frame is actually 1080p
                            // (root cause of the user report "every tap is 1080p").
                            // [uvcpad-resolution-mode] Display follows the actual: currentModeW/H updated to the
                            // actually negotiated values (including fallback values; the button is classified by aspect ratio, the screenshot filename uses the actual values);
                            // but memory is NOT written back — memory only records the user-selected mode (written in switchMode),
                            // hardware fallback does not change the memory, and after swapping hardware the next launch still requests the preset per the user's mode.
                            currentModeW = actual.width
                            currentModeH = actual.height
                        }
                        val requested = pendingModeW > 0 && pendingModeH > 0
                        // [uvcpad-ratio-toggle] Request-vs-actual negotiation log: grab logcat on a real device to confirm 16:9
                        // negotiation-failure fallback (AUSBC falls back via getSuitableSize nearest width, see the research report)
                        if (requested && actual != null) {
                            if (actual.width != pendingModeW || actual.height != pendingModeH) {
                                Log.w(
                                    TAG,
                                    "resolution fallback: requested ${pendingModeW}x$pendingModeH" +
                                        ", negotiated ${actual.width}x${actual.height}"
                                )
                            } else {
                                Log.i(
                                    TAG,
                                    "resolution negotiated OK: ${actual.width}x${actual.height}"
                                )
                            }
                        }
                        val text = if (actual != null && requested &&
                            (actual.width != pendingModeW || actual.height != pendingModeH)
                        ) {
                            // Negotiation-failure fallback: notify explicitly so the user does not think the switch succeeded
                            getString(
                                R.string.status_mode_fallback,
                                "$pendingModeW×$pendingModeH",
                                "${actual.width}×${actual.height}"
                            )
                        } else {
                            val w = actual?.width ?: currentModeW
                            val h = actual?.height ?: currentModeH
                            getString(R.string.status_opened, "$w×$h")
                        }
                        pendingModeW = 0
                        pendingModeH = 0
                        // The button label follows the actually negotiated size (truthfully shows 1080p instead of a fake 4:3 on fallback);
                        // USB attach may trigger OPENED before bindViews, so guard against uninitialized lateinit
                        if (::btnMode.isInitialized) {
                            updateModeButton()
                        }
                        // M1: no statusText view (M2 key bar hosts the persistent status);
                        // the opened state is surfaced as a toast
                        toast(text)
                        // Opened successfully: clear any previous error/permission hints (including the USB guidance hint)
                        usbHintActive = false
                        usbHintHandler.removeCallbacksAndMessages(null)
                        clearError()
                    }
                    ICameraStateCallBack.State.CLOSED -> {
                        // [uvcpad-consistency-p3] Unplug notification: CLOSED with no resolution-switch request in progress
                        // (pending cleared) = capture card unplugged/abnormally closed → notify via errorText; the CLOSED on the resolution-switch path
                        // is the normal intermediate state of closeCamera (pending non-zero, the subsequent OPENED clears the error),
                        // so no notification to avoid a false "disconnected" report. The AUSBC State enum has no DISCONNECTED; CLOSED is the only hangup signal.
                        if (pendingModeW == 0 && pendingModeH == 0) {
                            showError(getString(R.string.status_capture_card_disconnected))
                        }
                    }
                    ICameraStateCallBack.State.ERROR -> {
                        // Switch failure/camera exception: clear the in-flight switch request to avoid a leftover pending
                        // falsely reporting a "fallback" on a later OPENED
                        pendingModeW = 0
                        pendingModeH = 0
                        val errorMsg = getString(R.string.status_error, msg ?: "unknown")
                        // Capture-card errors (permission denied / open failure, etc.) are also shown in the error area for easy diagnosis
                        showError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                showError(getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ============ UI ============

    private fun bindViews() {
        errorText = findViewById(R.id.errorText)
        // The error area scrolls vertically (content becomes scrollable once maxHeight caps it)
        errorText.movementMethod = ScrollingMovementMethod()
        touchLayer = findViewById(R.id.touchLayer)
        rootLayout = findViewById(R.id.rootLayout)
        cameraViewContainer = findViewById(R.id.cameraViewContainer)
        // [uvcpad-touch-align]: AUSBC's AspectRatioTextureView scales fit-inside in onMeasure according to the video aspect ratio
        // (getGravity()=CENTER centers it in the container), so its layout bounds ARE the display area;
        // a global layout listener is registered so any layout change triggers the touch-layer alignment (first layout / resolution switch / rotation rebuild).
        // Note: the touch layer must NOT go inside cameraViewContainer — AUSBC initView() calls removeAllViews() on the container,
        // clearing the XML child views (confirmed in AUSBC 3.6.0 source, DESIGN §3.2).
        cameraViewContainer.viewTreeObserver.addOnGlobalLayoutListener(touchAlignLayoutListener)

        // ============ M2: drop triangle + key bar assembly (DESIGN §3.3/§3.4) ============
        dropTriangle = findViewById(R.id.dropTriangle)
        keyBar = findViewById(R.id.keyBar)
        keyBarController = KeyBarController(keyBar, prefs.autoHideMs)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnBt = findViewById(R.id.btnBt)
        btnAutoPair = findViewById(R.id.btnAutoPair)
        btnMode = findViewById(R.id.btnMode)
        btnCapture = findViewById(R.id.btnCapture)
        btnExit = findViewById(R.id.btnExit)

        // Triangle tap → key bar show/hide toggle (expanded→collapse; collapsing also resets the timer, DESIGN §3.3)
        dropTriangle.onToggle = { keyBarController.toggle() }
        // Any touch resets the auto-hide timer (DESIGN §3.4): key bar non-button areas + touch layer
        keyBar.onAreaTouch = { keyBarController.resetAutoHideTimer() }
        touchLayer.onAnyTouch = { keyBarController.resetAutoHideTimer() }

        setupKeyBarListeners()
    }

    /**
     * Switch the capture resolution preset (kept from hdmi2mp for the M2 key bar).
     * M1 note: the toolbar button/highlight params were dropped together with the toolbar
     * (adaptation ①); M2 key-bar buttons will call this directly.
     *
     * [uvcpad-prefs-mem2] Resolution setting decoupled from the capture-card state (user decision on 2026-08-12):
     * whether or not a card is plugged in, tapping the button always first updates currentModeW/H + writes SharedPreferences (memory),
     * and the button label follows immediately (the caller then calls updateModeButton()); when no card is plugged in, the setting is no longer rejected —
     * it only remembers and does not trigger updateResolution (meaningless without an open camera), with a light hint that it takes effect once a card is plugged in;
     * after plugging in, getCameraRequest() requests per the latest remembered values (AUSBC reopen/unplug-replug go through the same path, naturally satisfied).
     * The plugged-in path keeps the original behavior: pending → updateResolution → OPENED reads back the actually negotiated size.
     *
     * [uvcpad-resolution-mode] Memory-semantics change: prefs stores the **user-selected mode** (the mode parameter,
     * MODE_4_3 / MODE_16_9), no longer the actually negotiated fallback values (the OPENED read-back currentModeW/H is only used for
     * display and button classification). Hence hardware fallback cannot pollute the memory — after swapping hardware, the next launch still requests the preset per the user's mode.
     */
    private fun switchMode(width: Int, height: Int, mode: Int) {
        // The setting is always changeable: first update the current request size + remember the user-selected mode (resolutionMode);
        // taking effect with a plugged-in card depends on getCameraRequest()
        currentModeW = width
        currentModeH = height
        prefs.resolutionMode = mode
        if (isCameraOpened()) {
            // AUSBC 3.6.0: updateResolution is asynchronous — it internally closeCamera() and
            // re-opens the camera 1s later, auto-negotiating the closest supported size; it does
            // NOT throw on an unsupported size (only logs when the camera is not open / recording).
            // So the request target is recorded here and the actual negotiated result is read back
            // in onCameraState(OPENED) via getCurrentPreviewSize(); currentModeW/H are only updated
            // there, keeping the UI truthful to the real camera state (hdmi2mp P3-L1 rollback,
            // adapted for AUSBC's async switch).
            pendingModeW = width
            pendingModeH = height
            try {
                updateResolution(width, height)
            } catch (e: Exception) {
                // Defensive: AUSBC does not normally throw here, but surface any failure loudly
                pendingModeW = 0
                pendingModeH = 0
                val msg = getString(R.string.status_error, e.message ?: e.javaClass.simpleName)
                showError(msg)
                toast(msg)
            }
        } else {
            // No card plugged in: do not trigger updateResolution (meaningless without an open camera); only lightly hint that the memory took effect and will apply once a card is plugged in
            toast(getString(R.string.status_mode_pending, "$width×$height"))
        }
    }

    /** Capture a screenshot of the UVC frame (kept from hdmi2mp verbatim; key-bar entry lands in M2) */
    private fun captureJpg() {
        if (!isCameraOpened()) {
            toast(getString(R.string.status_waiting))
            return
        }
        // P3-EDGE fix: getExternalFilesDir may theoretically return null; fall back to the internal filesDir
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val dir = File(baseDir, "screenshots").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(dir, "uvcpad_${stamp}_${currentModeW}x${currentModeH}.jpg")

        // captureImage itself may throw (e.g. capture pipeline not ready): wrap everything, never crash silently
        try {
            captureImage(object : ICaptureCallBack {
                override fun onBegin() {
                    // The callback runs on the capture thread; UI operations like Toast must be
                    // posted to the main thread (otherwise "Can't toast on a thread that has not
                    // called Looper.prepare()" is thrown)
                    runOnUiThread { toast(getString(R.string.btn_capture) + "…") }
                }

                override fun onError(error: String?) {
                    val msg = getString(R.string.capture_failed, error ?: "unknown")
                    runOnUiThread {
                        toast(msg)
                        showError(msg)
                    }
                }

                override fun onComplete(path: String?) {
                    val saved = path ?: target.absolutePath
                    // MediaStore import involves file I/O; run it on a dedicated background thread to avoid blocking the main/capture thread
                    Thread {
                        val imported = try {
                            // Import into the system gallery (no permission needed on 29+; 23~28 need WRITE_EXTERNAL_STORAGE)
                            MediaStoreUtils.saveMediaStore(File(saved), this@MainActivity)
                            true
                        } catch (e: Exception) {
                            // A failed gallery import is non-blocking: the file stays in the app's private directory; the error is also shown in the error area
                            val msg = getString(R.string.capture_failed, e.message ?: e.javaClass.simpleName)
                            runOnUiThread {
                                toast(msg)
                                showError(msg)
                            }
                            false
                        }
                        if (imported) {
                            runOnUiThread { toast(getString(R.string.capture_saved, saved)) }
                        }
                    }.start()
                }
            }, target.absolutePath)
        } catch (e: Exception) {
            val msg = getString(R.string.capture_failed, e.message ?: e.javaClass.simpleName)
            showError(msg)
            toast(msg)
        }
    }

    // ============ Touch layer assembly (DESIGN §3.5) ============

    /**
     * Mount the gesture engine on the transparent touch layer (called on BT connect, main thread).
     * A fresh RelativeMouseSender is created for this connection; the ViewListener applies the
     * current speed preset (scrollSpeed goes through the SpeedLevel override, level 4 = 1.0f, DESIGN §3.6).
     */
    private fun setupTouchLayer(hidDevice: BluetoothHidDevice, host: BluetoothDevice) {
        val sender = RelativeMouseSender(hidDevice, host)
        val vListener = ViewListener(hidDevice, host, sender).also {
            it.mouseSpeed = currentSpeedLevel.mouse
            it.scrollSpeed = currentSpeedLevel.scroll
        }
        viewListener = vListener
        touchLayer.setGestureListener(vListener)
        // M2: refresh the Bluetooth button label after connecting (device name/connected)
        updateBtButton()
    }

    /**
     * Detach the gesture engine (called on BT disconnect, main thread).
     * DESIGN §3.7: detach the listener first — the sender is owned by the ViewListener, so
     * dropping the listener releases the sender with it; no report can be sent to a dead host.
     */
    private fun teardownTouchLayer() {
        viewListener = null
        touchLayer.setGestureListener(null)
        // M2: refresh the Bluetooth button label after disconnecting
        updateBtButton()
    }

    // ============ M2: key bar wiring (DESIGN §3.4) ============

    /**
     * M2 key-bar button wiring (DESIGN §3.4 table; the button set contains no keyboard settings items, Q2 ✅).
     * Every button resets the auto-hide timer before handling (same pattern as hdmi2mp "each button calls showToolbar() first":
     * tapping a button is an interaction, so the key bar stays expanded and the timer restarts).
     */
    private fun setupKeyBarListeners() {
        // --- Speed: 1️⃣–5️⃣ cycle (KeysJoy SelectDeviceActivity.setupToolbar logic) ---
        btnSpeed.text = currentSpeedLevel.emoji
        btnSpeed.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            val nextLevel = (currentSpeedLevel.level % 5) + 1
            currentSpeedLevel = SpeedLevel.forLevel(nextLevel)
            prefs.speedLevel = nextLevel
            btnSpeed.text = currentSpeedLevel.emoji
            viewListener?.let {
                it.mouseSpeed = currentSpeedLevel.mouse
                it.scrollSpeed = currentSpeedLevel.scroll
            }
            toast(getString(R.string.keybar_speed, currentSpeedLevel.emoji))
        }

        // --- Bluetooth: tap to connect/disconnect; long-press → multi-device switch showDeviceSwitcher (KeysJoy logic) ---
        updateBtButton()
        btnBt.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            val host = BluetoothController.hostDevice
            if (host != null) {
                // Connected → disconnect ([uvcpad-fix-p1] sets the manual-disconnect marker to suppress auto-reconnect after DISCONNECTED)
                BluetoothController.manualDisconnectFlag = true
                BluetoothController.tryDisconnect(host)
                BluetoothController.hostDevice = null
                updateBtButton()
                toast(getString(R.string.keybar_bt_disconnected))
            } else {
                // [uvcpad-consistency-p2] Service-not-connected / registration-failed state (btHid==null): tapping = user reconnect intent,
                // re-run the init chain (getProfileProxy → registerApp) instead of only toasting — otherwise after the "tap BT to retry"
                // hint the only recovery is backgrounding/restarting (P2-1)
                if (BluetoothController.btHid == null) {
                    BluetoothController.manualDisconnectFlag = false
                    // [uvcpad-l1] Only when init is actually issued (permission + adapter guards passed) show the "connecting" hint;
                    // otherwise (BT disabled / no permission, early return) a toast would mislead the user into thinking it is connecting
                    if (initBluetooth()) {
                        toast(getString(R.string.keybar_bt_connecting))
                    }
                    return@setOnClickListener
                }
                // Disconnected → try connect to the previously paired device
                BluetoothController.mpluggedDevice?.let { device ->
                    if (btConnectionState(device) ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {
                        // [uvcpad-fix-p1] Manual connect intent: clear the manual-disconnect marker before connecting
                        BluetoothController.manualDisconnectFlag = false
                        BluetoothController.tryConnect(device)
                        toast(getString(R.string.keybar_bt_connecting))
                    }
                } ?: toast(getString(R.string.keybar_bt_no_device))
            }
        }
        btnBt.setOnLongClickListener {
            keyBarController.resetAutoHideTimer()
            showDeviceSwitcher()
            true
        }

        // --- Auto-pair 🔗 (KeysJoy setupToolbar logic: autoPairFlag + reconnect loop) ---
        btnAutoPair.text = if (prefs.autoPair) "🔗" else "⛓️‍💥"
        btnAutoPair.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            val enabled = !prefs.autoPair
            prefs.autoPair = enabled
            BluetoothController.autoPairFlag = enabled
            btnAutoPair.text = if (enabled) "🔗" else "⛓️‍💥"
            if (enabled) {
                // [uvcpad-fix-p1] Re-enabling auto-pair is a new auto-connect intent → clear the manual-disconnect marker
                BluetoothController.manualDisconnectFlag = false
                BluetoothController.startAutoReconnect()
                // KeysJoy: immediately try connecting to the paired device when auto-pair is enabled
                BluetoothController.mpluggedDevice?.let { device ->
                    if (btConnectionState(device) ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {
                        BluetoothController.tryConnect(device)
                    }
                }
                toast(getString(R.string.keybar_auto_pair_on))
            } else {
                BluetoothController.stopAutoReconnect()
                toast(getString(R.string.keybar_auto_pair_off))
            }
        }

        // --- Resolution: 16:9 ↔ 4:3 (reuses switchMode; on failed rollback currentModeW/H stay unchanged → label unchanged) ---
        // [uvcpad-ratio-toggle] Bucket determination is by aspect ratio (isSixteenNine) instead of exact size comparison:
        // after a real device negotiates 1920×1080 down to 1600×1200 (4:3 ratio, not a preset value), the old exact-equality check
        // fails → the else branch always switches to 4:3 → the 16:9 branch is unreachable. By ratio: 1600×1200 counts as the 4:3 bucket,
        // tap → switch to the 16:9 preset (1920×1080); if the hardware still does not support it, OPENED reads back the fallback size and toasts.
        // [uvcpad-resolution-mode] The **user-selected mode** is written to prefs.resolutionMode on switch
        // (0=4:3 / 1=16:9); the hardware fallback value read back on OPENED is not written to memory.
        updateModeButton()
        btnMode.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            if (isSixteenNine(currentModeW, currentModeH)) {
                switchMode(MODE_4BY3_W, MODE_4BY3_H, UvcpadPrefs.MODE_4_3)
            } else {
                switchMode(MODE_1080P_W, MODE_1080P_H, UvcpadPrefs.MODE_16_9)
            }
            updateModeButton()
        }

        // --- Screenshot 📷 (hdmi2mp captureJpg reused verbatim) ---
        btnCapture.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            captureJpg()
        }

        // --- Exit ⏻: cleanup + finish (onDestroy → AUSBC clear() releases UVC) ---
        btnExit.setOnClickListener {
            keyBarController.destroy()
            finish()
        }
    }

    /** Bluetooth button label: shows the device name when connected, the default hint when not */
    private fun updateBtButton() {
        val host = BluetoothController.hostDevice
        val name = btDeviceName(host)
        btnBt.text = if (name.isNotEmpty()) getString(R.string.keybar_bt_connected, name)
            else getString(R.string.keybar_bt_default)
    }

    /**
     * 16:9 bucket determination: by aspect ratio rather than exact size.
     * [uvcpad-ratio-toggle] Real-device feedback: after 1920×1080 negotiation fails and falls back to 1600×1200 (4:3 ratio, non-preset value),
     * the old "exact equality" check fails → the 16:9 branch is unreachable. With ratio-based determination, 1600×1200 lands in the
     * 4:3 bucket and tapping can re-request the 16:9 preset.
     * Integer cross-multiplication comparison (W*9 >= H*16) avoids float errors: 1920×1080 / 1280×720 etc. match;
     * 1600×1200 / 1872×1404 / 1024×768 (4:3) do not.
     */
    private fun isSixteenNine(w: Int, h: Int): Boolean =
        w > 0 && h > 0 && w.toLong() * 9 >= h.toLong() * 16

    /** 4:3 aspect-ratio check (integer cross-multiplication): W*3 == H*4, e.g. 1600×1200, 1872×1404, 1024×768 */
    private fun isFourThree(w: Int, h: Int): Boolean =
        w > 0 && h > 0 && w.toLong() * 3 == h.toLong() * 4

    /**
     * Resolution button label: follows currentModeW/H (= the camera's actually negotiated size, read back on the OPENED callback).
     * [uvcpad-ratio-toggle] Classified by aspect ratio: 16:9 ratio shows "16:9", 4:3 ratio shows "4:3"
     * (including the negotiated fallback 1600×1200 — the user sees 4:3 and knows the current bucket);
     * rare other ratios (e.g. 5:4) show the actual size truthfully.
     */
    private fun updateModeButton() {
        btnMode.text = when {
            isSixteenNine(currentModeW, currentModeH) -> "16:9"
            isFourThree(currentModeW, currentModeH) -> "4:3"
            else -> "$currentModeW×$currentModeH"
        }
    }

    /**
     * Multi-device switch popup (copied from KeysJoy SelectDeviceActivity.showDeviceSwitcher, DESIGN §4.2 extracted snippet).
     * Invoked by long-pressing the Bluetooth button: lists paired devices; tap → switchTo() switch; plus a "📡 Make Discoverable" entry.
     */
    private fun showDeviceSwitcher() {
        if (!::btnBt.isInitialized) return
        val popup = PopupMenu(this, btnBt, Gravity.START)
        // [uvcpad-consistency-p3] The paired list (bondedDevices) is authoritative, pairedDevices is only a fallback:
        // the runtime cache may contain unbonded devices (never cleaned after unbonding); using the system's current paired list
        // avoids dead-device entries in the popup; without permission to read it, degrade to the runtime cache ([uvcpad-fix-p3] empty-list fallback semantics kept)
        val devices = try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList()?.ifEmpty {
                BluetoothController.pairedDevices.toList()
            } ?: BluetoothController.pairedDevices.toList()
        } catch (e: SecurityException) {
            BluetoothController.pairedDevices.toList()
        }
        val currentHost = BluetoothController.hostDevice

        // Connected device header (disabled)
        if (currentHost != null) {
            popup.menu.add(
                Menu.NONE, -1, 0, getString(R.string.keybar_popup_connected, btDeviceName(currentHost))
            ).isEnabled = false
        } else {
            popup.menu.add(Menu.NONE, -1, 0, getString(R.string.keybar_popup_no_device))
                .isEnabled = false
        }
        popup.menu.add(Menu.NONE, -1, 1, "──────────────").isEnabled = false

        // Make Discoverable menu item (KeysJoy verbatim: reflection setScanMode + system request)
        popup.menu.add(Menu.NONE, -2, 2, getString(R.string.keybar_popup_make_discoverable))

        // Paired devices list
        var itemId = 0
        for (device in devices) {
            val name = btDeviceName(device)
            val label =
                if (device.address == currentHost?.address) "▶ $name" else "  $name"
            popup.menu.add(Menu.NONE, itemId, itemId + 3, label)
            itemId++
        }
        if (devices.isEmpty()) {
            popup.menu.add(Menu.NONE, -1, 3, getString(R.string.keybar_popup_no_paired))
                .isEnabled = false
        }

        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == -2) {
                // Make Discoverable
                try {
                    BluetoothAdapter.getDefaultAdapter()?.javaClass?.getMethod(
                        "setScanMode",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )?.invoke(
                        BluetoothAdapter.getDefaultAdapter(),
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                        300
                    )
                } catch (_: Exception) {
                }
                try {
                    // [uvcpad-fix-p2] S+ ACTION_REQUEST_DISCOVERABLE needs BLUETOOTH_CONNECT: degrade with a hint when permission is missing
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    startActivity(intent)
                } catch (e: SecurityException) {
                    toast(getString(R.string.bt_permission_denied))
                }
                return@setOnMenuItemClickListener true
            }
            val idx = item.itemId
            if (idx in 0 until devices.size) {
                val target = devices[idx]
                if (target.address != currentHost?.address) {
                    // [uvcpad-last-device-click] Tapping the target device remembers it and persists immediately (not waiting for connection success: even a failed
                    // connection keeps the intent, so the next auto-connect still prefers it); the connection-success callback writes once more (double insurance, see the onStart wiring)
                    prefs.lastDeviceAddress = target.address
                    BluetoothController.lastDeviceAddress = target.address
                    BluetoothController.switchTo(target)
                    toast(getString(R.string.keybar_bt_switching, btDeviceName(target)))
                }
            }
            true
        }
        popup.show()
    }

    // ============ Touch-area alignment (uvcpad-touch-align: touch area = display area) ============

    /**
     * [uvcpad-touch-align] Aligns the touch layer to the actual display rectangle of the capture frame.
     *
     * Display area = AspectRatioTextureView's layout bounds (AUSBC scales it fit-inside in onMeasure by video aspect ratio
     * + getGravity()=CENTER centers it, confirmed in AUSBC 3.6.0 source), converted to rootLayout-relative coordinates and written to the touch layer's
     * LayoutParams (margin + exact size; alignToDisplayRect returns early internally when unchanged, no extra layout).
     *
     * Edge handling:
     * - camera view absent/not laid out (size 0) → the touch layer degrades to 0×0: no touch can land on it;
     * - touches outside the display area (black bars/letterbox) land on the non-clickable cameraViewContainer → dropped directly by the framework,
     *   producing no HID events;
     * - gesture continuity: ACTION_DOWN inside the display area → the event stream belongs to the touch layer (Android ownership model); when the finger slides out
     *   of the display area, MOVE keeps dispatching to this layer → drag is not lost (the ViewListener chain works as-is).
     */
    private fun syncTouchLayerBounds() {
        // [uvcpad-touch-align-fix] Type defense: AUSBC initView guarantees via removeAllViews + a single addView that
        // the container has only one child, the camera view; the camera view is created programmatically by getCameraView()
        // (AspectRatioTextureView, extends TextureView, has no resource id and cannot be found via findViewById),
        // so the child type is verified instead of implicitly trusting getChildAt(0). On a type mismatch (future container mixing in other child views),
        // treat it conservatively as "no camera view" (touch layer 0×0).
        val cameraView = cameraViewContainer.getChildAt(0)
            ?.takeIf { it is TextureView }
        if (cameraView == null || cameraView.width <= 0 || cameraView.height <= 0) {
            touchLayer.alignToDisplayRect(Rect(0, 0, 0, 0))
            return
        }
        val camPos = IntArray(2)
        val rootPos = IntArray(2)
        cameraView.getLocationInWindow(camPos)
        rootLayout.getLocationInWindow(rootPos)
        val left = camPos[0] - rootPos[0]
        val top = camPos[1] - rootPos[1]
        touchLayer.alignToDisplayRect(
            Rect(left, top, left + cameraView.width, top + cameraView.height)
        )
    }

    // ============ Serial permissions (DESIGN §1.4, hdmi2mp serial mode extended) ============

    /**
     * Serial permission requests: camera first (required for UVC); BT (S+) / location (≤30) /
     * storage (23~28) follow inside the result callbacks, so the system dialogs never queue up
     * on top of each other (hdmi2mp L2 fix, extended with the BT/location chain).
     */
    private fun requestPermissionsSequentially() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionDialogShown = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            requestBtPermissionsIfNeeded()
        }
    }

    private fun requestBtPermissionsIfNeeded() {
        // Android 12+ (S): BLUETOOTH_CONNECT + BLUETOOTH_SCAN requested together (KeysJoy splash mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needConnect = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
            val needScan = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
            if (needConnect || needScan) {
                permissionDialogShown = true
                btPermissionsLauncher.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                )
                return
            }
        }
        requestLocationPermissionIfNeeded()
    }

    private fun requestLocationPermissionIfNeeded() {
        // API≤30 needs ACCESS_COARSE_LOCATION for Bluetooth discovery; Android 12+ is exempt
        // because BLUETOOTH_SCAN declares neverForLocation (DESIGN §1.4)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionDialogShown = true
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                return
            }
        }
        requestStoragePermissionIfNeeded()
    }

    private fun requestStoragePermissionIfNeeded() {
        // Only API 23~28 need it; MediaStore on 29+ requires no permission
        if (Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionDialogShown = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // Chain tail on S+ / API 29–30: last dialog (if any) is done — run the first-launch
            // BT init that onStart skipped while dialogs were showing (P2 fix, uvcpad-P2-fix)
            maybeInitBluetoothAfterFirstLaunch()
        }
    }

    /**
     * First-launch chain tail (P2 fix, uvcpad-P2-fix): when the serial flow actually showed
     * permission dialogs, onStart returned early without BT init (permissions not yet granted).
     * Now that every required permission is granted, run the same guarded init (DESIGN §3.7).
     * No-op on later launches: the flag stays false because every permission was already
     * granted and onStart already ran the init.
     */
    private fun maybeInitBluetoothAfterFirstLaunch() {
        if (permissionDialogShown) {
            initBluetooth()
        }
    }

    /**
     * [uvcpad-fix-p2] Connect guard (relaxed on API≤30): the location permission only affects "discovering new devices", not
     * "connecting to paired devices" — this app does no active scanning (no startDiscovery), and on ≤30 BLUETOOTH/
     * BLUETOOTH_ADMIN are normal permissions (granted at install) → pass directly, so an over-strict location gate cannot
     * block connecting to already-paired devices. If the discovery/scan path is ever needed, use the strict guard (requires the location permission) separately.
     */
    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * [uvcpad-fix-p2] Safely reads the device name (falls back to the address without BLUETOOTH_CONNECT on S+, avoiding SecurityException)
     */
    @SuppressLint("MissingPermission")
    private fun btDeviceName(device: BluetoothDevice?): String {
        return try {
            device?.name ?: device?.address ?: ""
        } catch (e: SecurityException) {
            device?.address ?: ""
        }
    }

    /**
     * [uvcpad-fix-p2] Safely reads the connection state (S+ getConnectionState needs BLUETOOTH_CONNECT)
     */
    @SuppressLint("MissingPermission")
    private fun btConnectionState(device: BluetoothDevice): Int? {
        return try {
            BluetoothController.btHid?.getConnectionState(device)
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Checks the USB authorization state: when a capture card is attached but not yet
     * authorized, prompts the user to tap "Allow / Always allow" in the system USB
     * authorization dialog (AUSBC requests the permission itself; this is only guidance).
     * The hint is shown only once (no repeated toasts); it is cleared immediately once
     * the user grants permission (hdmi2mp verbatim).
     */
    private fun checkUsbPermissionHint(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        @Suppress("DEPRECATION")
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device == null) return
        if (usbManager.hasPermission(device)) {
            // The user has already granted permission via the system dialog (this path may also be reached before camera OPENED): clear the stale guidance hint
            usbHintHandler.removeCallbacksAndMessages(null)
            if (usbHintActive) {
                usbHintActive = false
                clearError()
            }
            return
        }
        if (!usbHintActive) {
            usbHintActive = true
            val msg = getString(R.string.usb_permission_hint)
            showError(msg)
            toast(msg)
            // [uvcpad-fix-p2] C1: reset the hint marker 30s after the dialog is ignored → the next attach intent (or replug)
            // re-prompts; the reset task is cancelled on grant (hasPermission branch / camera OPENED / onDestroy)
            usbHintHandler.removeCallbacksAndMessages(null)
            usbHintHandler.postDelayed({
                usbHintActive = false
            }, 30_000L)
        }
    }

    /** Shows an error in the error area (main-thread safe; the area starts GONE and appears only on errors) */
    private fun showError(msg: String) {
        runOnUiThread {
            if (::errorText.isInitialized) {
                errorText.visibility = View.VISIBLE
                errorText.text = msg
            }
        }
    }

    /** Clears the error area (called when the camera opens successfully) */
    private fun clearError() {
        runOnUiThread {
            if (::errorText.isInitialized) {
                errorText.visibility = View.GONE
            }
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(msg: String) {
        // [uvcpad-toast-singleton] Singleton reuse: cancel the still-showing/queued old toast before showing a new message,
        // avoiding the Android Toast default queueing mechanism piling up hints (a new message cannot push out the old one)
        // [uvcpad-fix-p3] Create the Toast with applicationContext: the static sToast no longer holds an Activity reference
        sToast?.cancel()
        sToast = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).also { it.show() }
    }
}
