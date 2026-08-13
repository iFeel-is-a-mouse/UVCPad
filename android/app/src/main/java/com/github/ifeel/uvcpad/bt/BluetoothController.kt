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
     * [uvcpad-fix-p1] Manual-disconnect marker: set to true when btnBt tap disconnects, suppressing the three auto-connect paths
     * (onConnectionStateChanged DISCONNECTED auto-reconnect / startAutoReconnect loop /
     * onAppStatusChanged auto connect); cleared to false by any manual connect intent (btnBt connect, switchTo,
     * onAppStatusChanged auto connect, enabling autoPair) and on any successful connection.
     * @Volatile: written on the main thread, read on binder callback threads.
     */
    @Volatile
    var manualDisconnectFlag = false

    var statusListener: ((String) -> Unit)? = null

    private fun updateStatus(msg: String) {
        // [uvcpad-consistency-p3] Status messages are high-frequency (connect/disconnect/register/discovery all trigger them, and they also go through toasts) → Log.d
        Log.d(TAG, "Status: $msg")
        statusListener?.invoke(msg)
    }

    var mpluggedDevice :BluetoothDevice? = null

    /**
     * [uvcpad-last-device] Address of the most recently successfully connected device (preferred target for auto-connect).
     * Injected from prefs by MainActivity at startup; written back to prefs via [lastDeviceConnectedListener] on every successful connection.
     * null = no memory, auto-connect falls back to mpluggedDevice (the device passed by the system callback).
     */
    var lastDeviceAddress: String? = null

    /** [uvcpad-last-device] Connection-success callback: MainActivity uses it to persist the most recently connected device to prefs */
    var lastDeviceConnectedListener: ((BluetoothDevice) -> Unit)? = null

    /** [uvcpad-consistency-p2] Callback when the remembered address is invalidated (no longer in the paired list): MainActivity uses it to clear the persisted prefs memory */
    var lastDeviceAddressRemovedListener: (() -> Unit)? = null

    /** List of paired devices for device switching */
    val pairedDevices = mutableListOf<BluetoothDevice>()
    var targetSwitchDevice: BluetoothDevice? = null

    // Auto-reconnect loop fields
    private var reconnectHandler: Handler? = null
    private val RECONNECT_INTERVAL_MS = 5000L

    /** [uvcpad-consistency-p3] Timeout to force-reset initInProgress when getProfileProxy never calls back */
    private val INIT_TIMEOUT_MS = 3000L

    /** [uvcpad-fix-p2] Short-circuits duplicate init when getProfileProxy has been issued but not yet returned (onStart+onResume double-call on first launch) */
    private var initInProgress = false

    /** [uvcpad-consistency-p3] Theoretical-hang safety net: force-resets initInProgress if no callback within 3s (prevents permanently short-circuiting later inits) */
    private var initTimeoutHandler: Handler? = null

    /** [uvcpad-fix-p2] applicationContext used for permission checks (injected at init; does not hold the Activity) */
    private var appContext: Context? = null

    /** [uvcpad-fix-p2] Auto-retry marker for a failed S1 registerApp (retries only once) */
    private var registerAppRetried = false

    /** [uvcpad-p2-retry-cleanup] registerApp 3s retry Handler (onServiceDisconnected cancels the pending retry) */
    private var registerRetryHandler: Handler? = null

    /** [uvcpad-p2-retry-cleanup] registerApp 3s retry Runnable (stored in a field so it can be cancelled; reads this.btHid at execution, does not capture the old proxy) */
    private var registerRetryRunnable: Runnable? = null

    /** [uvcpad-fix-p2] Single-retry marker for a failed S8 device-switch connect */
    private var switchRetryScheduled = false

    /** [uvcpad-consistency-p2] S8 switch-retry Handler/Runnable (stored in fields so switchTo can cancel: switching again within 3s is not overridden by the old retry) */
    private var switchRetryHandler: Handler? = null
    private var switchRetryRunnable: Runnable? = null

    private var deviceListener: ((BluetoothHidDevice, BluetoothDevice)->Unit)? = null
    private var disconnectListener: (()->Unit)? = null

    /**
     * [uvcpad-last-device] Auto-connect target device: prefers the most recently successfully connected device (lastDeviceAddress;
     * in multi-device scenarios the system onAppStatusChanged may always return the earliest paired device → always connects to the wrong one);
     * falls back to the system-callback mpluggedDevice when there is no memory or the address is invalid. Returns null when there is no auto-connect target.
     *
     * [uvcpad-consistency-p2] The remembered address is returned only if it is still in the paired list (bondedDevices):
     * after swapping/re-pairing devices the old address is no longer in the paired list, yet getRemoteDevice still returns an object without
     * checking the pairing state → 5s infinite reconnect loop to a dead device, never falling back to the default device (P2-2 idle spin).
     * Not in the list → clear the memory (in-memory + prefs) and fall back to mpluggedDevice; when there is no permission to read the paired
     * list, conservatively return the remembered address (preserves old behavior, avoids wrongly clearing the memory).
     */
    fun resolveAutoConnectTarget(): BluetoothDevice? {
        val lastAddr = lastDeviceAddress
        if (!lastAddr.isNullOrEmpty()) {
            try {
                val bonded = try {
                    btAdapter.bondedDevices
                } catch (e: SecurityException) {
                    Log.e(TAG, "bondedDevices blocked: no BLUETOOTH_CONNECT", e)
                    null
                }
                val remembered = bonded?.firstOrNull { it.address == lastAddr }
                if (remembered != null) {
                    return remembered
                }
                if (bonded != null) {
                    // The paired list is readable and does not contain the remembered address: the old device was removed/re-paired → clear the memory (in-memory + prefs) and fall back to the default device
                    Log.w(TAG, "Last device $lastAddr no longer bonded, clearing last-device memory")
                    lastDeviceAddress = null
                    lastDeviceAddressRemovedListener?.invoke()
                } else {
                    // Cannot verify without permission: conservatively return the remembered address to avoid wrongly clearing the memory
                    return btAdapter.getRemoteDevice(lastAddr)
                }
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
                // [uvcpad-fix-p1] No auto-reconnect after the user manually disconnects
                if (manualDisconnectFlag) {
                    stopAutoReconnect()
                    return
                }
                // [uvcpad-fix-p2] S7: terminate the loop when the BT service/proxy is unavailable (btHid null), avoiding infinite idle spin
                if (btHid == null) {
                    stopAutoReconnect()
                    return
                }
                // [uvcpad-last-device] The reconnect target also goes through resolveAutoConnectTarget(): prefers the most recent device
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
                Log.d(TAG, "Auto-reconnect attempt to ${deviceName(device)}")
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
        // [uvcpad-fix-p2] On first launch, onStart initBluetooth and onResume btHid==null run two getProfileProxy calls in the same frame:
        // short-circuit the second call while one is already issued and not returned; the flag is cleared on onServiceConnected/onServiceDisconnected
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
        // [uvcpad-consistency-p3] Theoretical-hang safety net: getProfileProxy may theoretically never call back
        // (neither onServiceConnected nor onServiceDisconnected fires) → initInProgress stays true and short-circuits
        // all later inits (the only recovery is a restart). Force-reset the flag after 3s; the normal callback path explicitly cancels the timer.
        initTimeoutHandler?.removeCallbacksAndMessages(null)
        initTimeoutHandler = Handler(Looper.getMainLooper())
        initTimeoutHandler?.postDelayed({
            if (initInProgress) {
                initInProgress = false
                Log.w(TAG, "getProfileProxy timed out, resetting initInProgress")
            }
        }, INIT_TIMEOUT_MS)
        appContext = ctx.applicationContext
        try {
            btAdapter.getProfileProxy(ctx.applicationContext, this, BluetoothProfile.HID_DEVICE)
        } catch (e: Throwable) {
            initInProgress = false
            initTimeoutHandler?.removeCallbacksAndMessages(null)
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
                initTimeoutHandler?.removeCallbacksAndMessages(null)
                btHid = null
                // [uvcpad-p2-retry-cleanup] Reset the registerApp retry marker + cancel the pending 3s retry:
                // otherwise the leftover Runnable holds the old proxy and, failing the retry 3s later, sets btHid=null, harming the new proxy after reconnect
                registerAppRetried = false
                registerRetryHandler?.removeCallbacksAndMessages(null)
                registerRetryHandler = null
                registerRetryRunnable = null
                // [uvcpad-fix-p1] State residue cleanup: hostDevice/mpluggedDevice cleared together to avoid
                // the next init thinking it is still connected; disconnectListener notifies the UI to tear down the touch layer (MainActivity side
                // wrapped in runOnUiThread, safe to call from binder threads); the reconnect loop is also stopped (btHid is already invalid)
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
            initTimeoutHandler?.removeCallbacksAndMessages(null)

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
            // [uvcpad-fix-p2] S1: a failed registerApp does not linger — auto-retries once after 3s; on another failure it sets
            // btHid=null so later onResume/manual re-entry can re-init (the user can trigger it again instead of being stuck)
            if (tryRegisterApp(btHid)) {
                registerAppRetried = false
                updateStatus("HID registered. Search 'uvcpad' on target device")
            } else {
                updateStatus("HID reg failed, check BT permissions")
                if (!registerAppRetried) {
                    registerAppRetried = true
                    // [uvcpad-p2-retry-cleanup] The retry Runnable is stored in a field (the original anonymous postDelayed could not be cancelled);
                    // it reads this.btHid at execution instead of capturing the old proxy; onServiceDisconnected cancels the pending retry
                    registerRetryHandler?.removeCallbacksAndMessages(null)
                    registerRetryHandler = Handler(Looper.getMainLooper())
                    registerRetryRunnable = object : Runnable {
                        override fun run() {
                            registerRetryRunnable = null
                            // Under an extreme race the service may have disconnected without being cancelled → btHid may be null; give up directly without harming anything
                            val proxy = this@BluetoothController.btHid ?: return
                            if (tryRegisterApp(proxy)) {
                                registerAppRetried = false
                                updateStatus("HID registered. Search 'uvcpad' on target device")
                            } else {
                                registerAppRetried = false
                                this@BluetoothController.btHid = null
                                updateStatus("HID reg failed, tap BT to retry")
                            }
                        }
                    }
                    registerRetryRunnable?.let { registerRetryHandler?.postDelayed(it, 3000L) }
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
            Log.d(TAG, "Connection state ${when(state) {
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
                    // [uvcpad-fix-p1] A successful connection means the manual-disconnect intent is expired
                    manualDisconnectFlag = false
                    // [uvcpad-last-device] Every successful connection updates the "most recent" memory (including manual switch success:
                    // the next auto-connect prefers the new device), persisted to prefs via the callback
                    lastDeviceAddress = device.address
                    lastDeviceConnectedListener?.invoke(device)
                    device?.let { dev ->
                        if (pairedDevices.none { it.address == dev.address }) {
                            pairedDevices.add(dev)
                        }
                    }

                    deviceListener?.let { listener ->
                        // [uvcpad-consistency-p3] btHid may be nulled momentarily (service-disconnect race): a non-null assertion would NPE, use safe unwrapping instead
                        btHid?.let { hid -> listener.invoke(hid, device) }
                    }
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
                    Log.d(TAG, "Switching to device: ${deviceName(toSwitch)}")
                    updateStatus("Switching...")
                    // [uvcpad-fix-p2] S8: single retry 3s after a switch-target connect failure (no longer stuck when autoPair is off)
                    tryConnectWithRetry(toSwitch)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnectListener?.invoke()
                    updateStatus("Disconnected, waiting...")
                    // [uvcpad-fix-p1] No auto-reconnect after a manual disconnect
                    if (autoPairFlag && !manualDisconnectFlag && mpluggedDevice != null) {
                        Log.d(TAG, "Device disconnected, starting auto-reconnect loop")
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
            Log.d(TAG, "onAppStatusChanged: registered=$registered, device=$pluggedDevice")
            
            if (registered) {
                mpluggedDevice = pluggedDevice
                pluggedDevice?.let { dev ->
                    if (pairedDevices.none { it.address == dev.address }) {
                        pairedDevices.add(dev)
                    }
                }
                updateStatus("HID app registered")
                if (autoPairFlag) {
                    // [uvcpad-fix-p1] No auto-connect after a manual disconnect (the marker stays until the user manually connects/switches/re-enables autoPair);
                    // when not marked, this path itself is an auto-connect intent → clear the marker and connect
                    if (!manualDisconnectFlag) {
                        manualDisconnectFlag = false
                        // [uvcpad-last-device] The auto-connect target prefers the "most recently successfully connected device" (resolveAutoConnectTarget),
                        // falling back to the system-callback mpluggedDevice when there is no memory; no auto-connect when the target is null
                        val target = resolveAutoConnectTarget()
                        if (target != null) {
                            tryConnect(target)
                            Log.d(TAG, "Auto-connecting to device: ${deviceName(target)}")
                        } else {
                            Log.w(TAG, "onAppStatusChanged: no auto-connect target")
                        }
                    } else {
                        Log.d(TAG, "onAppStatusChanged: manual disconnect active, skip auto-connect")
                    }
                }
            } else {
                updateStatus("HID app unregistered, re-registering...")
                btHid?.let { hid ->
                    try {
                        hid.registerApp(sdpRecord, null, qosOut, { it.run() }, this)
                        Log.d(TAG, "Re-register attempt sent")
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
        // [uvcpad-consistency-p2] A new switch intent immediately invalidates the old switch's 3s retry:
        // otherwise the old Runnable reconnects to the old device 3s later, overriding the user's new choice (switching again within 3s scenario)
        switchRetryScheduled = false
        switchRetryHandler?.removeCallbacksAndMessages(null)
        switchRetryRunnable = null
        if (device.address == hostDevice?.address) {
            Log.i(TAG, "switchTo: already connected to ${deviceName(device)}, skipping")
            return
        }
        // [uvcpad-fix-p1] A user-initiated switch is a new connect intent → the manual-disconnect marker becomes invalid
        manualDisconnectFlag = false
        targetSwitchDevice = device
        stopAutoReconnect()
        hostDevice?.let { tryDisconnect(it) }
            ?: run {
                // No current connection, connect directly
                targetSwitchDevice = null
                // [uvcpad-fix-p2] S8: single retry 3s after a connect failure
                tryConnectWithRetry(device)
            }
    }

    /**
     * [uvcpad-fix-p1] Clears all callback listeners (deviceListener/disconnectListener/statusListener):
     * called from MainActivity.onDestroy to prevent the singleton from holding lambdas referencing a destroyed Activity (leak).
     */
    fun clearListeners() {
        deviceListener = null
        disconnectListener = null
        statusListener = null
    }

    // ============ Permission guard + Bluetooth op wrappers ([uvcpad-fix-p2] MissingPermission + runtime SecurityException safety net) ============

    /**
     * Unified Bluetooth-connect permission guard: checks BLUETOOTH_CONNECT on S+, always true on ≤30
     * (BLUETOOTH is a normal permission granted at install; location only affects discovery, not connecting to paired devices).
     */
    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return appContext?.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** Safely reads the device name: falls back to the address on missing permission/exceptions, avoiding an S+ SecurityException crash */
    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice?): String {
        return try {
            device?.name ?: device?.address ?: "?"
        } catch (e: SecurityException) {
            device?.address ?: "?"
        }
    }

    /** Safely reads the connection state (getConnectionState needs BLUETOOTH_CONNECT on S+) */
    @SuppressLint("MissingPermission")
    private fun connectionState(device: BluetoothDevice?): Int? {
        return try {
            btHid?.getConnectionState(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "getConnectionState blocked: no BLUETOOTH_CONNECT", e)
            null
        }
    }

    /** Permission-guarded + exception-safe connect (no retry semantics; retries are handled by the startAutoReconnect loop) */
    fun tryConnect(device: BluetoothDevice?) {
        if (device == null || !hasConnectPermission()) return
        try {
            btHid?.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect blocked: no BLUETOOTH_CONNECT", e)
        }
    }

    /** Permission-guarded + exception-safe disconnect */
    fun tryDisconnect(device: BluetoothDevice?) {
        if (device == null || !hasConnectPermission()) return
        try {
            btHid?.disconnect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect blocked: no BLUETOOTH_CONNECT", e)
        }
    }

    /**
     * [uvcpad-fix-p2] Device-switch-specific connect: single retry 3s after failure
     * (a failed switch no longer gets stuck when autoPair is off; with autoPair on the reconnect loop already covers it).
     * [uvcpad-consistency-p2] The retry Runnable is stored in a field (the original anonymous postDelayed could not be cancelled):
     * switchTo removes callbacks at the start to cancel the old retry, so switching again within 3s is not overridden by the old retry.
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
            switchRetryHandler?.removeCallbacksAndMessages(null)
            switchRetryHandler = Handler(Looper.getMainLooper())
            switchRetryRunnable = object : Runnable {
                override fun run() {
                    switchRetryScheduled = false
                    switchRetryRunnable = null
                    try {
                        btHid?.connect(device)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "connect retry blocked: no BLUETOOTH_CONNECT", e)
                    }
                }
            }
            switchRetryRunnable?.let { switchRetryHandler?.postDelayed(it, 3000L) }
        }
    }

    /** [uvcpad-fix-p2] S1 registerApp wrapper: exceptions count as failure, returns whether registration succeeded */
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
