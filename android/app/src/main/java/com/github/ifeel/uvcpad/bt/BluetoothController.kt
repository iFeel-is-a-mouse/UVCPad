package com.github.ifeel.uvcpad.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.ifeel.uvcpad.bt.reports.FeatureReport


@Suppress("MemberVisibilityCanBePrivate")
object BluetoothController: BluetoothHidDevice.Callback(), BluetoothProfile.ServiceListener {

    const val TAG = "BluetoothController"

    val featureReport = FeatureReport()



    override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
        try {
            super.onSetReport(device, type, id, data)
        } catch (e: Throwable) {
            Log.e(TAG, "onSetReport crash", e)
        }
    }


    override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
        try {
            super.onGetReport(device, type, id, bufferSize)
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE) {
                featureReport.wheelResolutionMultiplier = true
                featureReport.acPanResolutionMultiplier = true
                btHid?.replyReport(device, type, FeatureReport.ID, featureReport.bytes)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onGetReport crash", e)
        }
    }


    val btAdapter by lazy {
        BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("BluetoothAdapter unavailable")
    }
    var btHid: BluetoothHidDevice? = null
    var hostDevice: BluetoothDevice? = null
    var autoPairFlag = false

    /**
     * [uvcpad-fix-p1] 手动断开标记：btnBt 点击断开时置 true，抑制三处自动连接
     * （onConnectionStateChanged DISCONNECTED 自动重连 / startAutoReconnect 循环 /
     * onAppStatusChanged 自动 connect）；手动连接意图（btnBt 连接、switchTo、
     * onAppStatusChanged 自动连、开启 autoPair）及任何连接成功时清 false。
     * @Volatile：主线程写、binder 回调线程读。
     */
    @Volatile
    var manualDisconnectFlag = false

    var statusListener: ((String) -> Unit)? = null

    private fun updateStatus(msg: String) {
        Log.i(TAG, "Status: $msg")
        statusListener?.invoke(msg)
    }

    var mpluggedDevice :BluetoothDevice? = null

    /**
     * [uvcpad-last-device] 最近成功连接过的设备地址（自动连接优先目标）。
     * 由 MainActivity 启动时从 prefs 注入；每次连接成功经 [lastDeviceConnectedListener] 回写 prefs。
     * null = 无记忆，自动连接回退 mpluggedDevice（系统回调传入的设备）。
     */
    var lastDeviceAddress: String? = null

    /** [uvcpad-last-device] 连接成功回调：MainActivity 用它把最近连接设备持久化到 prefs */
    var lastDeviceConnectedListener: ((BluetoothDevice) -> Unit)? = null

    /** List of paired devices for device switching */
    val pairedDevices = mutableListOf<BluetoothDevice>()
    var targetSwitchDevice: BluetoothDevice? = null

    // Auto-reconnect loop fields
    private var reconnectHandler: Handler? = null
    private val RECONNECT_INTERVAL_MS = 5000L

    /** [uvcpad-fix-p2] getProfileProxy 已发出未返回时短路重复 init（首启 onStart+onResume 双调） */
    private var initInProgress = false

    /** [uvcpad-fix-p2] 供权限检查使用的 applicationContext（init 时注入，不持有 Activity） */
    private var appContext: Context? = null

    /** [uvcpad-fix-p2] S1 registerApp 失败自动重试标记（仅重试一次） */
    private var registerAppRetried = false

    /** [uvcpad-fix-p2] S8 切换设备 connect 失败的单次重试标记 */
    private var switchRetryScheduled = false

    private var deviceListener: ((BluetoothHidDevice, BluetoothDevice)->Unit)? = null
    private var disconnectListener: (()->Unit)? = null

    /**
     * [uvcpad-last-device] 自动连接的目标设备：优先最近成功连接过的设备（lastDeviceAddress，
     * 多设备场景下系统 onAppStatusChanged 可能总返回最早配对的设备 → 总连错设备）；
     * 无记忆或地址非法时回退系统回调的 mpluggedDevice。返回 null 表示当前无可自动连接目标。
     */
    fun resolveAutoConnectTarget(): BluetoothDevice? {
        val lastAddr = lastDeviceAddress
        if (!lastAddr.isNullOrEmpty()) {
            try {
                return btAdapter.getRemoteDevice(lastAddr)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid last device address: $lastAddr, falling back to plugged device")
            }
        }
        return mpluggedDevice
    }

    /**
     * Start a periodic reconnect loop that retries every [RECONNECT_INTERVAL_MS]
     * until the device reconnects, autopair is disabled, or the plugged device goes null.
     */
    fun startAutoReconnect() {
        stopAutoReconnect()
        reconnectHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!autoPairFlag) {
                    stopAutoReconnect()
                    return
                }
                // [uvcpad-fix-p1] 用户手动断开后不再自动重连
                if (manualDisconnectFlag) {
                    stopAutoReconnect()
                    return
                }
                // [uvcpad-fix-p2] S7：蓝牙服务/代理不可用（btHid 为空）时终止循环，避免无限空转
                if (btHid == null) {
                    stopAutoReconnect()
                    return
                }
                // [uvcpad-last-device] 重连目标同样走 resolveAutoConnectTarget()：优先最近连接设备
                val device = resolveAutoConnectTarget() ?: run {
                    stopAutoReconnect()
                    return
                }
                val state = connectionState(device)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    stopAutoReconnect()
                    return
                }
                // Still disconnected, try reconnect
                Log.i(TAG, "Auto-reconnect attempt to ${deviceName(device)}")
                tryConnect(device)
                reconnectHandler?.postDelayed(this, RECONNECT_INTERVAL_MS)
            }
        }
        reconnectHandler?.post(runnable)
    }

    /**
     * Stop the auto-reconnect loop and release the handler.
     */
    fun stopAutoReconnect() {
        reconnectHandler?.removeCallbacksAndMessages(null)
        reconnectHandler = null
    }

    fun init(ctx: Context) {
        // [uvcpad-fix-p2] 首启 onStart initBluetooth + onResume btHid==null 同帧连跑双 getProfileProxy：
        // 已发出未返回时短路第二次调用；onServiceConnected/onServiceDisconnected 时清标志
        if (initInProgress) {
            Log.i(TAG, "init already in progress, skipping duplicate getProfileProxy")
            return
        }
        if (btHid != null) {
            // Check if still connected — btHid may be stale after app switch
            try {
                if (btHid?.connectedDevices.isNullOrEmpty()) {
                    Log.w(TAG, "btHid has no connected devices, forcing re-init")
                    btHid = null
                    hostDevice = null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "btHid is stale, resetting", e)
                btHid = null
                hostDevice = null
            }
        }
        if (btHid != null) return
        initInProgress = true
        appContext = ctx.applicationContext
        try {
            btAdapter.getProfileProxy(ctx.applicationContext, this, BluetoothProfile.HID_DEVICE)
        } catch (e: Throwable) {
            initInProgress = false
            Log.e(TAG, "getProfileProxy failed", e)
            updateStatus("BT init failed: ${e.message}")
        }
    }

    fun getSender(callback: (BluetoothHidDevice, BluetoothDevice)->Unit) {
        btHid?.let { hidd ->
            hostDevice?.let { host ->
                callback(hidd, host)
                return
            }
        }
        deviceListener = callback
    }


    fun getDisconnector(callback: ()->Unit) {

        disconnectListener = callback
    }

    /*****************************************************/
    /** BluetoothProfile.ServiceListener implementation **/
    /*****************************************************/

    override fun onServiceDisconnected(profile: Int) {
        try {
            Log.e(TAG, "Service disconnected!")
            if (profile == BluetoothProfile.HID_DEVICE) {
                initInProgress = false
                btHid = null
                // [uvcpad-fix-p1] 状态残留清理：hostDevice/mpluggedDevice 一并清空，避免下次
                // init 时误以为仍连接；disconnectListener 通知 UI 拆除触控层（MainActivity 侧
                // runOnUiThread 包裹，binder 线程调用安全）；重连循环一并停止（btHid 已失效）
                hostDevice = null
                mpluggedDevice = null
                stopAutoReconnect()
                disconnectListener?.invoke()
            }
            updateStatus("BT service disconnected")
        } catch (e: Throwable) {
            Log.e(TAG, "onServiceDisconnected crash", e)
        }
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        try {
            Log.i(TAG, "Connected to service")
            if (profile != BluetoothProfile.HID_DEVICE) {
                Log.wtf(TAG, "WTF? $profile")
                return
            }
            initInProgress = false

            val btHid = proxy as? BluetoothHidDevice
            if (btHid == null) {
                Log.wtf(TAG, "WTF? Proxy received but it's not BluetoothHidDevice")
                return
            }
            this.btHid = btHid
            // Set the discoverable name to "uvcpad" so PCs see it instead of the
            // device model name (e.g. "MatePad Paper"). Must be re-applied on every
            // service connect because the system may reset the name after reboot.
            try {
                btAdapter.setName("uvcpad")
            } catch (e: Throwable) {
                Log.e(TAG, "setName failed", e)
            }
            // [uvcpad-fix-p2] S1：registerApp 失败不滞留——3s 后自动重试一次；仍失败则置
            // btHid=null 允许后续 onResume/手动重进重新 init（用户可再次触发，不再卡死）
            if (tryRegisterApp(btHid)) {
                registerAppRetried = false
                updateStatus("HID registered. Search 'uvcpad' on target device")
            } else {
                updateStatus("HID reg failed, check BT permissions")
                if (!registerAppRetried) {
                    registerAppRetried = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (tryRegisterApp(btHid)) {
                            registerAppRetried = false
                            updateStatus("HID registered. Search 'uvcpad' on target device")
                        } else {
                            registerAppRetried = false
                            this.btHid = null
                            updateStatus("HID reg failed, tap BT to retry")
                        }
                    }, 3000L)
                } else {
                    registerAppRetried = false
                    this.btHid = null
                }
            }
            try {
                // setScanMode is a hidden API absent from the compileSdk 36 android.jar stubs;
                // use reflection (same pattern as KeysJoy SelectDeviceActivity "Make Discoverable")
                val method = BluetoothAdapter::class.java.getMethod(
                    "setScanMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                method.invoke(btAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 300000)
                updateStatus("Discoverable (5 min)")
            } catch (e: Throwable) {
                Log.e(TAG, "setScanMode failed (hidden API)", e)
                updateStatus("Warning: not discoverable, tap to enable")
            }
        } catch (e: Throwable) {
            initInProgress = false
            Log.e(TAG, "onServiceConnected crash", e)
        }
    }



    /************************************************/
    /** BluetoothHidDevice.Callback implementation **/
    /************************************************/



    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        try {
            super.onConnectionStateChanged(device, state)
            Log.i(TAG, "Connection state ${when(state) {
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"

                else -> state.toString()
            }}")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (device != null) {
                    hostDevice = device
                    stopAutoReconnect()
                    // [uvcpad-fix-p1] 连接成功 = 手动断开意图已过期
                    manualDisconnectFlag = false
                    // [uvcpad-last-device] 每次连接成功都更新"最近连接"记忆（含手动切换成功：
                    // 下次自动连接优先新设备），并经回调持久化到 prefs
                    lastDeviceAddress = device.address
                    lastDeviceConnectedListener?.invoke(device)
                    device?.let { dev ->
                        if (pairedDevices.none { it.address == dev.address }) {
                            pairedDevices.add(dev)
                        }
                    }

                    deviceListener?.invoke(btHid!!, device)
                    updateStatus("Connected: ${deviceName(device)}")

                    //deviceListener = null
                } else {
                    Log.e(TAG, "Device not connected")
                }
            } else {
                hostDevice = null
                val toSwitch = targetSwitchDevice
                if (toSwitch != null) {
                    targetSwitchDevice = null
                    Log.i(TAG, "Switching to device: ${deviceName(toSwitch)}")
                    updateStatus("Switching...")
                    // [uvcpad-fix-p2] S8：切换目标 connect 失败 3s 后单次重试（autoPair off 时不再卡死）
                    tryConnectWithRetry(toSwitch)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnectListener?.invoke()
                    updateStatus("Disconnected, waiting...")
                    // [uvcpad-fix-p1] 手动断开后不自动重连
                    if (autoPairFlag && !manualDisconnectFlag && mpluggedDevice != null) {
                        Log.i(TAG, "Device disconnected, starting auto-reconnect loop")
                        startAutoReconnect()
                    }
                }

            }
        } catch (e: Throwable) {
            Log.e(TAG, "onConnectionStateChanged crash", e)
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        try {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.i(TAG, "onAppStatusChanged: registered=$registered, device=$pluggedDevice")
            
            if (registered) {
                mpluggedDevice = pluggedDevice
                pluggedDevice?.let { dev ->
                    if (pairedDevices.none { it.address == dev.address }) {
                        pairedDevices.add(dev)
                    }
                }
                updateStatus("HID app registered")
                if (autoPairFlag) {
                    // [uvcpad-fix-p1] 手动断开后不自动连接（标记保留到用户手动连接/切换/重开 autoPair）；
                    // 未标记时本路径本身即自动连接意图 → 清标记后连接
                    if (!manualDisconnectFlag) {
                        manualDisconnectFlag = false
                        // [uvcpad-last-device] 自动连接目标优先"最近成功连接过的设备"（resolveAutoConnectTarget），
                        // 无记忆时回退系统回调的 mpluggedDevice；目标为 null 时不自动连
                        val target = resolveAutoConnectTarget()
                        if (target != null) {
                            tryConnect(target)
                            Log.i(TAG, "Auto-connecting to device: ${deviceName(target)}")
                        } else {
                            Log.w(TAG, "onAppStatusChanged: no auto-connect target")
                        }
                    } else {
                        Log.i(TAG, "onAppStatusChanged: manual disconnect active, skip auto-connect")
                    }
                }
            } else {
                updateStatus("HID app unregistered, re-registering...")
                btHid?.let { hid ->
                    try {
                        hid.registerApp(sdpRecord, null, qosOut, { it.run() }, this)
                        Log.i(TAG, "Re-register attempt sent")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Re-register failed", e)
                        updateStatus("HID re-register failed: ${e.message}")
                        btHid = null
                        hostDevice = null
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onAppStatusChanged crash", e)
        }
    }





    /*************/
    /** Garbage **/
    /*************/

    private val sdpRecord by lazy {
        BluetoothHidDeviceAppSdpSettings(
            "uvcpad",
            "Mobile BController",
            "bla",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            DescriptorCollection.MOUSE_RELATIVE_WITH_SCROLL
        )
    }



    /**
     * Switch to a different paired device.
     * Disconnects current device; connection to target happens
     * automatically in onConnectionStateChanged callback.
     */
    fun switchTo(device: BluetoothDevice) {
        Log.i(TAG, "switchTo: requested switch to ${deviceName(device)}")
        if (device.address == hostDevice?.address) {
            Log.i(TAG, "switchTo: already connected to ${deviceName(device)}, skipping")
            return
        }
        // [uvcpad-fix-p1] 用户主动切换 = 新的连接意图 → 手动断开标记失效
        manualDisconnectFlag = false
        targetSwitchDevice = device
        stopAutoReconnect()
        hostDevice?.let { tryDisconnect(it) }
            ?: run {
                // No current connection, connect directly
                targetSwitchDevice = null
                // [uvcpad-fix-p2] S8：connect 失败 3s 后单次重试
                tryConnectWithRetry(device)
            }
    }

    /**
     * [uvcpad-fix-p1] 清空全部回调监听（deviceListener/disconnectListener/statusListener）：
     * MainActivity.onDestroy 调用，防止单例持有已销毁 Activity 的 lambda 引用泄漏。
     */
    fun clearListeners() {
        deviceListener = null
        disconnectListener = null
        statusListener = null
    }

    // ============ 权限守卫 + 蓝牙操作封装（[uvcpad-fix-p2] MissingPermission + 运行时 SecurityException 兜底） ============

    /**
     * 统一蓝牙连接权限守卫：S+ 检查 BLUETOOTH_CONNECT，≤30 恒 true
     * （BLUETOOTH 是普通权限安装即授予；定位只影响发现，不影响已配对连接）。
     */
    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return appContext?.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 安全读取设备名：无权限/异常时回退地址，避免 S+ SecurityException 崩溃 */
    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice?): String {
        return try {
            device?.name ?: device?.address ?: "?"
        } catch (e: SecurityException) {
            device?.address ?: "?"
        }
    }

    /** 安全读取连接状态（getConnectionState 在 S+ 需要 BLUETOOTH_CONNECT） */
    @SuppressLint("MissingPermission")
    private fun connectionState(device: BluetoothDevice?): Int? {
        return try {
            btHid?.getConnectionState(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "getConnectionState blocked: no BLUETOOTH_CONNECT", e)
            null
        }
    }

    /** 权限守卫 + 异常兜底的 connect（无重试语义；重试由 startAutoReconnect 循环负责） */
    fun tryConnect(device: BluetoothDevice?) {
        if (device == null || !hasConnectPermission()) return
        try {
            btHid?.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect blocked: no BLUETOOTH_CONNECT", e)
        }
    }

    /** 权限守卫 + 异常兜底的 disconnect */
    fun tryDisconnect(device: BluetoothDevice?) {
        if (device == null || !hasConnectPermission()) return
        try {
            btHid?.disconnect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect blocked: no BLUETOOTH_CONNECT", e)
        }
    }

    /**
     * [uvcpad-fix-p2] S8 切换设备专用 connect：失败 3s 后单次重试
     * （autoPair off 时切换失败不再卡死；autoPair on 时重连循环本就覆盖）。
     */
    private fun tryConnectWithRetry(device: BluetoothDevice?) {
        if (device == null || !hasConnectPermission()) return
        val ok = try {
            btHid?.connect(device) == true
        } catch (e: SecurityException) {
            Log.e(TAG, "connect blocked: no BLUETOOTH_CONNECT", e)
            false
        }
        if (!ok && !switchRetryScheduled) {
            switchRetryScheduled = true
            Log.w(TAG, "connect to ${deviceName(device)} failed, retrying in 3s")
            Handler(Looper.getMainLooper()).postDelayed({
                switchRetryScheduled = false
                try {
                    btHid?.connect(device)
                } catch (e: SecurityException) {
                    Log.e(TAG, "connect retry blocked: no BLUETOOTH_CONNECT", e)
                }
            }, 3000L)
        }
    }

    /** [uvcpad-fix-p2] S1 registerApp 封装：异常视为失败，返回是否注册成功 */
    private fun tryRegisterApp(proxy: BluetoothHidDevice): Boolean {
        return try {
            proxy.registerApp(sdpRecord, null, qosOut, { it.run() }, this)
        } catch (e: Throwable) {
            Log.e(TAG, "registerApp threw", e)
            false
        }
    }

    private val qosOut by lazy {
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
    }

}
