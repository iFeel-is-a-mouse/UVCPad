package com.github.ifeel.uvcpad

import android.Manifest
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
        // [uvcpad-toast-singleton] Toast 全局单例：新消息先 cancel 旧消息再显示 → 最新优先、不排队堆积
        private var sToast: Toast? = null
    }

    // [uvcpad-default-4by3-mem] 默认初始 4:3（1872×1404）。onCreate 会用 SharedPreferences
    // 记忆值覆盖（首次启动无记忆 = 保持 4:3 默认；记忆了上次选择则恢复上次尺寸）。
    private var currentModeW = MODE_4BY3_W
    private var currentModeH = MODE_4BY3_H

    /**
     * 分辨率切换请求目标（AUSBC 3.6.0 updateResolution 是异步的：closeCamera + 1s 后重开相机，
     * 内部自动协商最接近支持尺寸；不抛异常）。实际协商结果在 onCameraState(OPENED) 里通过
     * getCurrentPreviewSize() 回读——用 pending 记录请求目标，供 OPENED 回调判断是否回退。
     * 0 表示无进行中的切换请求。
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
     * [uvcpad-touch-align] 布局同步：每次布局变化（首次布局 / switchMode 分辨率切换 / 旋转重建）
     * 把触控层收缩到采集画面实际显示矩形（= AspectRatioTextureView 的布局 bounds，DESIGN §3.2）。
     */
    private val touchAlignLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        syncTouchLayerBounds()
    }

    private lateinit var prefs: UvcpadPrefs

    /** Speed preset applied to the gesture engine at connection time (DESIGN §3.6: level 4 = 1.0f default) */
    private var currentSpeedLevel: SpeedLevel = SpeedLevel.DEFAULT

    /** 当前手势引擎引用：速度按钮实时更新 mouseSpeed/scrollSpeed（DESIGN §3.4）；连接时创建、断开时置空 */
    private var viewListener: ViewListener? = null

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
        prefs = UvcpadPrefs(this)
        // [uvcpad-default-4by3-mem] 恢复上次分辨率选择：savedInstanceState（旋转等配置变更 /
        // 进程重建，值最新）优先，否则取 SharedPreferences 记忆值；首次启动无记忆 =
        // 默认 4:3 1872×1404（UvcpadPrefs.DEFAULT_RESOLUTION_W/H）。
        if (savedInstanceState != null) {
            currentModeW = savedInstanceState.getInt(KEY_MODE_W, prefs.resolutionW)
            currentModeH = savedInstanceState.getInt(KEY_MODE_H, prefs.resolutionH)
        } else {
            currentModeW = prefs.resolutionW
            currentModeH = prefs.resolutionH
        }
        // 初始请求与 switchMode 共用同一套异步回读逻辑（v0.2.3）：把请求目标记为 pending，
        // OPENED 回读实际协商尺寸——首次 4:3 请求若 EDID 不支持而回退，同样如实显示并提示。
        pendingModeW = currentModeW
        pendingModeH = currentModeH
        currentSpeedLevel = SpeedLevel.forLevel(prefs.speedLevel)
        // Auto-pair toggle (KeysJoy pattern): sync flag + start the reconnect loop when enabled
        BluetoothController.autoPairFlag = prefs.autoPair
        if (prefs.autoPair) {
            BluetoothController.startAutoReconnect()
        }
        // [uvcpad-last-device] 最近连接设备记忆（多设备自动连接选择）：启动时注入 prefs 记忆值；
        // 每次连接成功经 lastDeviceConnectedListener 回写 prefs（手动切换成功也计入 →
        // 下次自动连接优先新设备，符合"最近连接"语义）
        BluetoothController.lastDeviceAddress = prefs.lastDeviceAddress
        BluetoothController.lastDeviceConnectedListener = { device ->
            prefs.lastDeviceAddress = device.address
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
        // [uvcpad-last-device] 清理连接成功回调：避免单例持有已销毁 Activity 引用
        BluetoothController.lastDeviceConnectedListener = null
        // M2: 清除按键栏自动隐藏计时器与动画（hdmi2mp: removeCallbacksAndMessages 模式）
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
            // [uvcpad-default-4by3-mem] 请求尺寸跟随 currentModeW/H（onCreate 恢复的记忆值；
            // 首次启动 = 4:3 1872×1404），不再硬编码 1080p。
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
                            // 以相机实际协商尺寸为准：AUSBC 会回退到最接近支持尺寸
                            // （如 4:3 档 1872×1404 在无此分辨率的设备上回退 1920×1080），
                            // UI 必须跟随真实协商结果，否则按钮显示假 4:3、画面实为 1080p
                            // （用户反馈"点击都是 1080p"的根因）。
                            currentModeW = actual.width
                            currentModeH = actual.height
                            // [uvcpad-default-4by3-mem] 记忆上次选择：以实际协商结果为准写入
                            // （协商回退的尺寸也如实记忆），下次启动直接请求该尺寸。
                            prefs.saveResolution(currentModeW, currentModeH)
                        }
                        val requested = pendingModeW > 0 && pendingModeH > 0
                        // [uvcpad-ratio-toggle] 请求 vs 实际协商日志：真机抓 logcat 确认 16:9
                        // 协商失败回退（AUSBC 走 getSuitableSize 最近宽度回退，见调研报告）
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
                            // 协商失败回退：明确提示，不让用户误以为切换成功
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
                        // 按钮文案跟随实际协商尺寸（失败回退时如实显示 1080p 而非假 4:3）；
                        // USB attach 可能在 bindViews 前触发 OPENED，需防 lateinit 未初始化
                        if (::btnMode.isInitialized) {
                            updateModeButton()
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
                        // 切换失败/相机异常：清掉进行中的切换请求，避免残留 pending
                        // 在后续 OPENED 中误报"回退"
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
        // [uvcpad-touch-align]：AUSBC 的 AspectRatioTextureView 在 onMeasure 中按视频宽高比
        // fit-inside 自缩放（getGravity()=CENTER 在容器内居中），其布局 bounds 就是显示区域；
        // 注册全局布局监听，任何布局变化都触发触控层对齐（首次布局 / 分辨率切换 / 旋转重建）。
        // 注意：触控层不能放进 cameraViewContainer —— AUSBC initView() 会对容器 removeAllViews()
        // 清空 XML 子 View（AUSBC 3.6.0 源码确认，DESIGN §3.2）。
        cameraViewContainer.viewTreeObserver.addOnGlobalLayoutListener(touchAlignLayoutListener)

        // ============ M2: 下拉三角 + 按键栏装配（DESIGN §3.3/§3.4） ============
        dropTriangle = findViewById(R.id.dropTriangle)
        keyBar = findViewById(R.id.keyBar)
        keyBarController = KeyBarController(keyBar, prefs.autoHideMs)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnBt = findViewById(R.id.btnBt)
        btnAutoPair = findViewById(R.id.btnAutoPair)
        btnMode = findViewById(R.id.btnMode)
        btnCapture = findViewById(R.id.btnCapture)
        btnExit = findViewById(R.id.btnExit)

        // 三角点击 → 按键栏显隐 toggle（已展开→收起，收起即重置计时，DESIGN §3.3）
        dropTriangle.onToggle = { keyBarController.toggle() }
        // 任意触摸重置自动隐藏计时（DESIGN §3.4）：按键栏非按钮区域 + 触控层
        keyBar.onAreaTouch = { keyBarController.resetAutoHideTimer() }
        touchLayer.onAnyTouch = { keyBarController.resetAutoHideTimer() }

        setupKeyBarListeners()
    }

    /**
     * Switch the capture resolution preset (kept from hdmi2mp for the M2 key bar).
     * M1 note: the toolbar button/highlight params were dropped together with the toolbar
     * (改造点①); M2 key-bar buttons will call this directly.
     *
     * [uvcpad-prefs-mem2] 分辨率设置与采集卡状态解耦（用户 2026-08-12 拍板）：
     * 无论是否插卡，点击按钮总是先更新 currentModeW/H + 写入 SharedPreferences（记忆），
     * 按钮文案立即跟随（调用方随后 updateModeButton()）；未插卡时不再拒绝设置——
     * 只记忆不触发 updateResolution（相机未开，无意义），轻提示插卡后生效；
     * 插卡后 getCameraRequest() 按最新记忆值请求（AUSBC 重开/拔插走同一路径，自然满足）。
     * 已插卡路径保持原行为：pending → updateResolution → OPENED 回读实际协商尺寸 →
     * saveResolution 同步记忆（回退尺寸也如实记忆，覆盖本次预写值）。
     */
    private fun switchMode(width: Int, height: Int) {
        // 设置总是可变更：先更新记忆（currentModeW/H + prefs），插卡生效依赖 getCameraRequest()
        currentModeW = width
        currentModeH = height
        prefs.saveResolution(width, height)
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
            // 未插卡：不触发 updateResolution（相机未开，无意义），仅轻提示记忆已生效、插卡后生效
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
     * current speed preset (scrollSpeed 走 SpeedLevel 覆盖，level 4 = 1.0f，DESIGN §3.6).
     */
    private fun setupTouchLayer(hidDevice: BluetoothHidDevice, host: BluetoothDevice) {
        val sender = RelativeMouseSender(hidDevice, host)
        val vListener = ViewListener(hidDevice, host, sender).also {
            it.mouseSpeed = currentSpeedLevel.mouse
            it.scrollSpeed = currentSpeedLevel.scroll
        }
        viewListener = vListener
        touchLayer.setGestureListener(vListener)
        // M2: 连接后刷新蓝牙按钮文案（设备名/已连接）
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
        // M2: 断开后刷新蓝牙按钮文案
        updateBtButton()
    }

    // ============ M2: key bar wiring (DESIGN §3.4) ============

    /**
     * M2 按键栏按钮接线（DESIGN §3.4 表格；按钮集合不含任何键盘设置项，Q2 ✅）。
     * 每个按钮处理前先重置自动隐藏计时（与 hdmi2mp "每个按钮先 showToolbar()" 同模式：
     * 点击按钮 = 交互，按键栏保持展开并重新计时）。
     */
    private fun setupKeyBarListeners() {
        // --- 速度：1️⃣–5️⃣ 循环（KeysJoy SelectDeviceActivity.setupToolbar 逻辑）---
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

        // --- 蓝牙：点击连接/断开；长按 → 多设备切换 showDeviceSwitcher（KeysJoy 逻辑）---
        updateBtButton()
        btnBt.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            val host = BluetoothController.hostDevice
            if (host != null) {
                // Connected → disconnect
                BluetoothController.btHid?.disconnect(host)
                BluetoothController.hostDevice = null
                updateBtButton()
                toast(getString(R.string.keybar_bt_disconnected))
            } else {
                // Disconnected → try connect to the previously paired device
                BluetoothController.mpluggedDevice?.let { device ->
                    if (BluetoothController.btHid?.getConnectionState(device) ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {
                        BluetoothController.btHid?.connect(device)
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

        // --- 自动配对 🔗（KeysJoy setupToolbar 逻辑：autoPairFlag + 重连循环）---
        btnAutoPair.text = if (prefs.autoPair) "🔗" else "⛓️💥"
        btnAutoPair.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            val enabled = !prefs.autoPair
            prefs.autoPair = enabled
            BluetoothController.autoPairFlag = enabled
            btnAutoPair.text = if (enabled) "🔗" else "⛓️💥"
            if (enabled) {
                BluetoothController.startAutoReconnect()
                // KeysJoy: 开启自动配对时立即尝试连接已配对设备
                BluetoothController.mpluggedDevice?.let { device ->
                    if (BluetoothController.btHid?.getConnectionState(device) ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {
                        BluetoothController.btHid?.connect(device)
                    }
                }
                toast(getString(R.string.keybar_auto_pair_on))
            } else {
                BluetoothController.stopAutoReconnect()
                toast(getString(R.string.keybar_auto_pair_off))
            }
        }

        // --- 分辨率：16:9 ↔ 4:3（switchMode 复用现有；失败回滚时 currentModeW/H 不变 → 文案不变）---
        // [uvcpad-ratio-toggle] 档位判断改为按宽高比（isSixteenNine），不再精确比较尺寸：
        // 真机 1920×1080 协商失败回退 1600×1200（4:3 比例、非预设值）后，旧代码精确相等判断
        // 失效 → else 分支永远切 4:3 → 16:9 分支进不去。按比例后：1600×1200 视为 4:3 档，
        // 点击 → 切 16:9 预设（1920×1080）；若硬件仍不支持则 OPENED 回读回退尺寸并 toast 提示。
        updateModeButton()
        btnMode.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            if (isSixteenNine(currentModeW, currentModeH)) {
                switchMode(MODE_4BY3_W, MODE_4BY3_H)
            } else {
                switchMode(MODE_1080P_W, MODE_1080P_H)
            }
            updateModeButton()
        }

        // --- 截图 📷（hdmi2mp captureJpg 原样复用）---
        btnCapture.setOnClickListener {
            keyBarController.resetAutoHideTimer()
            captureJpg()
        }

        // --- 退出 ⏻：清理 + finish（onDestroy → AUSBC clear() 释放 UVC）---
        btnExit.setOnClickListener {
            keyBarController.destroy()
            finish()
        }
    }

    /** 蓝牙按钮文案：已连接显示设备名，未连接显示默认提示 */
    private fun updateBtButton() {
        val host = BluetoothController.hostDevice
        btnBt.text = host?.name?.let { getString(R.string.keybar_bt_connected, it) }
            ?: getString(R.string.keybar_bt_default)
    }

    /**
     * 16:9 档位判断：按宽高比而非精确尺寸。
     * [uvcpad-ratio-toggle] 真机反馈：1920×1080 协商失败回退 1600×1200（4:3 比例、非预设值）
     * 后，旧代码“精确相等”判断失效 → 16:9 分支永远进不去。按比例判断后 1600×1200 归入
     * 4:3 档，点击即可重新请求 16:9 预设。
     * 整数交叉相乘比较（W*9 >= H*16）避免 float 误差：1920×1080 / 1280×720 等命中；
     * 1600×1200 / 1872×1404 / 1024×768（4:3）不满足。
     */
    private fun isSixteenNine(w: Int, h: Int): Boolean =
        w > 0 && h > 0 && w.toLong() * 9 >= h.toLong() * 16

    /** 4:3 宽高比判断（整数交叉相乘）：W*3 == H*4，如 1600×1200、1872×1404、1024×768 */
    private fun isFourThree(w: Int, h: Int): Boolean =
        w > 0 && h > 0 && w.toLong() * 3 == h.toLong() * 4

    /**
     * 分辨率按钮文案：跟随 currentModeW/H（= 相机实际协商尺寸，OPENED 回调回读）。
     * [uvcpad-ratio-toggle] 按比例归类：16:9 比例显示 "16:9"、4:3 比例显示 "4:3"
     * （含协商回退的 1600×1200——用户看到 4:3 即知当前处于 4:3 档）；
     * 罕见其他比例（如 5:4）如实显示实际尺寸。
     */
    private fun updateModeButton() {
        btnMode.text = when {
            isSixteenNine(currentModeW, currentModeH) -> "16:9"
            isFourThree(currentModeW, currentModeH) -> "4:3"
            else -> "$currentModeW×$currentModeH"
        }
    }

    /**
     * 多设备切换弹窗（复制 KeysJoy SelectDeviceActivity.showDeviceSwitcher，DESIGN §4.2 提取片段）。
     * 长按蓝牙按钮唤出：列出已配对设备，点击 → switchTo() 切换；另有 "📡 Make Discoverable" 入口。
     */
    private fun showDeviceSwitcher() {
        if (!::btnBt.isInitialized) return
        val popup = PopupMenu(this, btnBt, Gravity.START)
        val devices = BluetoothController.pairedDevices.toList()
        val currentHost = BluetoothController.hostDevice

        // Connected device header (disabled)
        if (currentHost != null) {
            popup.menu.add(
                Menu.NONE, -1, 0, getString(R.string.keybar_popup_connected, currentHost.name)
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
            val label =
                if (device.address == currentHost?.address) "▶ ${device.name}" else "  ${device.name}"
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
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                startActivity(intent)
                return@setOnMenuItemClickListener true
            }
            val idx = item.itemId
            if (idx in 0 until devices.size) {
                val target = devices[idx]
                if (target.address != currentHost?.address) {
                    // [uvcpad-last-device-click] 点击目标设备即记忆并落盘（不等连接成功：连接失败也
                    // 记住意图，下次自动连接仍优先尝试）；连接成功回调另有一次回写（双保险，见 onStart 接线）
                    prefs.lastDeviceAddress = target.address
                    BluetoothController.lastDeviceAddress = target.address
                    BluetoothController.switchTo(target)
                    toast(getString(R.string.keybar_bt_switching, target.name))
                }
            }
            true
        }
        popup.show()
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
        // [uvcpad-touch-align-fix] 类型防御：AUSBC initView 用 removeAllViews + 单个 addView 保证
        // 容器只有相机视图一个子 View；相机视图由 getCameraView() 程序化创建
        // （AspectRatioTextureView，extends TextureView，无 resource id，无法 findViewById），
        // 故校验子 View 类型而非隐式信任 getChildAt(0)。类型不符（未来容器混入其他子 View）时
        // 按"无相机视图"保守处理（触控层 0×0）。
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
        // [uvcpad-toast-singleton] 单例复用：取消仍在展示/排队的旧 toast 再显示新消息，
        // 避免 Android Toast 默认排队机制导致提示堆积（新消息冲不掉旧的）
        sToast?.cancel()
        sToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }
}
