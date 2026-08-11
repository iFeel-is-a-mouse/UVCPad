package com.github.ifeel.uvcpad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
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
 *    pipeline kept (key-bar entry lands in M2).
 * 2. Touch chain (from KeysJoy, mouse-only): Bluetooth HID registered as a mouse
 *    (BluetoothController, DESIGN §4.2); once connected, the full-screen TransparentTouchLayer
 *    is wired to the ViewListener gesture engine; on disconnect the listener is detached
 *    before anything else (DESIGN §3.7).
 *
 * M1 adaptations vs hdmi2mp MainActivity (all documented in journey.md):
 * - 改造点① toolbar buttons removed (btnMode1080p/btnMode4by3/btnCapture/btnExit/topOverlay
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
    }

    private var currentModeW = MODE_1080P_W
    private var currentModeH = MODE_1080P_H

    private lateinit var errorText: TextView
    private lateinit var touchLayer: TransparentTouchLayer
    private lateinit var rootLayout: View
    private lateinit var cameraViewContainer: ViewGroup

    /**
     * [uvcpad-touch-align] 布局同步：每次布局变化（首次布局 / switchMode 分辨率切换 / 旋转重建）
     * 把触控层收缩到采集画面实际显示矩形（= AspectRatioTextureView 的布局 bounds，DESIGN §3.2）。
     */
    private val touchAlignLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        syncTouchLayerBounds()
    }

    private lateinit var prefs: UvcpadPrefs

    /** Speed preset applied to the gesture engine at connection time (DESIGN §3.6: level 4 = 1.0f default) */
    private var currentSpeedLevel: SpeedLevel = SpeedLevel.DEFAULT

    // Whether the USB permission guidance hint is currently shown: avoids spamming toasts on
    // every attach intent; cleared as soon as the user grants permission (from hdmi2mp verbatim)
    private var usbHintActive = false

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
        // Restore the previously selected resolution mode (keeps the user's choice when the process is recreated)
        if (savedInstanceState != null) {
            currentModeW = savedInstanceState.getInt(KEY_MODE_W, MODE_1080P_W)
            currentModeH = savedInstanceState.getInt(KEY_MODE_H, MODE_1080P_H)
        }
        prefs = UvcpadPrefs(this)
        currentSpeedLevel = SpeedLevel.forLevel(prefs.speedLevel)
        // Auto-pair toggle (KeysJoy pattern): sync flag + start the reconnect loop when enabled
        BluetoothController.autoPairFlag = prefs.autoPair
        if (prefs.autoPair) {
            BluetoothController.startAutoReconnect()
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
     */
    private fun initBluetooth() {
        if (!checkBluetoothPermissions()) return
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) {
            toast(getString(R.string.bt_disabled_hint))
            return
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
    }

    override fun onDestroy() {
        // DESIGN §3.7: stop auto-reconnect + clear the status listener + drop the crash-dialog ref
        BluetoothController.stopAutoReconnect()
        BluetoothController.statusListener = null
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
            .setPreviewWidth(MODE_1080P_W)
            .setPreviewHeight(MODE_1080P_H)
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
                        val text = if (actual != null) {
                            getString(R.string.status_opened, "${actual.width}×${actual.height}")
                        } else {
                            getString(R.string.status_opened, "$currentModeW×$currentModeH")
                        }
                        // M1: no statusText view (M2 key bar hosts the persistent status);
                        // the opened state is surfaced as a toast
                        toast(text)
                        // Opened successfully: clear any previous error/permission hints (including the USB guidance hint)
                        usbHintActive = false
                        clearError()
                    }
                    ICameraStateCallBack.State.CLOSED -> {
                        // No statusText in M1; nothing to render on close
                    }
                    ICameraStateCallBack.State.ERROR -> {
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
        // [uvcpad-touch-align]：AUSBC 的 AspectRatioTextureView 在 onMeasure 中按视频宽高比
        // fit-inside 自缩放（getGravity()=CENTER 在容器内居中），其布局 bounds 就是显示区域；
        // 注册全局布局监听，任何布局变化都触发触控层对齐（首次布局 / 分辨率切换 / 旋转重建）。
        // 注意：触控层不能放进 cameraViewContainer —— AUSBC initView() 会对容器 removeAllViews()
        // 清空 XML 子 View（AUSBC 3.6.0 源码确认，DESIGN §3.2）。
        cameraViewContainer.viewTreeObserver.addOnGlobalLayoutListener(touchAlignLayoutListener)
    }

    /**
     * Switch the capture resolution preset (kept from hdmi2mp for the M2 key bar).
     * M1 note: the toolbar button/highlight params were dropped together with the toolbar
     * (改造点①); M2 key-bar buttons will call this directly.
     */
    private fun switchMode(width: Int, height: Int) {
        if (!isCameraOpened()) {
            toast(getString(R.string.status_waiting))
            return
        }
        // AUSBC: internally stopPreview + startPreview, auto-negotiating the closest supported size.
        // A failed switch is not silent: the exception is caught and shown in the error area;
        // on failure the mode/text stay unchanged, keeping the UI consistent with the camera's
        // actual negotiated state (hdmi2mp P3-L1 rollback, verbatim)
        try {
            updateResolution(width, height)
            currentModeW = width
            currentModeH = height
            toast(getString(R.string.status_opened, "$width×$height"))
        } catch (e: Exception) {
            val msg = getString(R.string.status_error, e.message ?: e.javaClass.simpleName)
            showError(msg)
            toast(msg)
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
     * current speed preset (scrollSpeed 走 SpeedLevel 覆盖，level 4 = 1.0f，DESIGN §3.6).
     */
    private fun setupTouchLayer(hidDevice: BluetoothHidDevice, host: BluetoothDevice) {
        val sender = RelativeMouseSender(hidDevice, host)
        val vListener = ViewListener(hidDevice, host, sender).also {
            it.mouseSpeed = currentSpeedLevel.mouse
            it.scrollSpeed = currentSpeedLevel.scroll
        }
        touchLayer.setGestureListener(vListener)
    }

    /**
     * Detach the gesture engine (called on BT disconnect, main thread).
     * DESIGN §3.7: detach the listener first — the sender is owned by the ViewListener, so
     * dropping the listener releases the sender with it; no report can be sent to a dead host.
     */
    private fun teardownTouchLayer() {
        touchLayer.setGestureListener(null)
    }

    // ============ Touch-area alignment (uvcpad-touch-align: 触控区域 = 显示区域) ============

    /**
     * [uvcpad-touch-align] 把触控层对齐到采集画面实际显示矩形。
     *
     * 显示区域 = AspectRatioTextureView 的布局 bounds（AUSBC onMeasure 按视频宽高比 fit-inside
     * 自缩放 + getGravity()=CENTER 居中，AUSBC 3.6.0 源码确认），相对 rootLayout 换算后写入触控层
     * LayoutParams（margin + 精确尺寸；值未变化时 alignToDisplayRect 内部直接返回，无额外布局）。
     *
     * 边界处理：
     * - 相机视图不存在/未布局（尺寸 0）→ 触控层退化为 0×0：任何触摸都落不到本层；
     * - 显示区域外的触摸（黑边/留白）落在非 clickable 的 cameraViewContainer → 框架直接丢弃，
     *   不产生任何 HID 事件；
     * - 手势连续性：ACTION_DOWN 落在显示区域内 → 事件流归触控层（Android 归属模型），手指滑出
     *   显示区域后 MOVE 仍持续派发给本层 → 拖拽不丢失（ViewListener 链路原样工作）。
     */
    private fun syncTouchLayerBounds() {
        val cameraView = cameraViewContainer.getChildAt(0)
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
     * Checks whether the BT runtime permissions required by this app are granted
     * (KeysJoy pattern, adjusted for the neverForLocation exemption on S+).
     */
    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
