package io.github.teccheck.gear360app.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.teccheck.gear360app.bluetooth.BTCameraConfigMessage
import io.github.teccheck.gear360app.bluetooth.BTCameraInfoMessage
import io.github.teccheck.gear360app.bluetooth.BTCommandActionConfig
import io.github.teccheck.gear360app.bluetooth.BTCommandRequest
import io.github.teccheck.gear360app.bluetooth.BTDateTimeRequest
import io.github.teccheck.gear360app.bluetooth.BTMessage2
import io.github.teccheck.gear360app.bluetooth.BTRemoteShotResponse
import io.github.teccheck.gear360app.bluetooth.BTWidgetInfoRequest
import io.github.teccheck.gear360app.bluetooth.BTWidgetInfoResponseCamera
import io.github.teccheck.gear360app.bluetooth.MessageHandler
import io.github.teccheck.gear360app.bluetooth.MessageLog
import io.github.teccheck.gear360app.bluetooth.MessageSender
import io.github.teccheck.gear360app.transport.Gear360ControlTransport
import io.github.teccheck.gear360app.transport.Gear360TransportFactory
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import io.github.teccheck.gear360app.utils.DeviceDescription
import io.github.teccheck.gear360app.utils.SettingsHelper
import io.github.teccheck.gear360app.utils.WifiUtils

private const val TAG_CONNECTION = "G360-CONNECTION"
private const val TAG_BT = "G360-BT"
private const val TAG_CAPTURE = "G360-CAPTURE"
private const val TAG_PROTOCOL = "G360-PROTOCOL"
private const val TAG_WIFI = "G360-WIFI"
private const val PRIMARY_JSON_CHANNEL = 204
private const val CAPTURE_MODE_CHANGE_DELAY_MS = 650L
private const val CAPTURE_COMMAND_TIMEOUT_MS = 20_000L
private const val PROTOCOL_SYNC_TIMEOUT_MS = 10_000L
private const val PROTOCOL_SAFE_READ_DELAY_MS = 3_000L

class Gear360Service : Service() {
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var controlTransport: Gear360ControlTransport
    private lateinit var messageSender: MessageSender
    private lateinit var connectionOrchestrator: Gear360ConnectionOrchestrator

    private var currentConnectionState = ConnectionState.INVALID
    private var connectAfterPairDevice: DeviceDescription? = null
    private var bondReceiverRegistered = false
    private var captureTimeoutRunnable: Runnable? = null
    private var protocolSyncTimeoutRunnable: Runnable? = null
    private var protocolSafeReadRunnable: Runnable? = null
    private var protocolReceivedChannel204 = false
    private var protocolReceivedConfig = false
    private var protocolChannel204RxCount = 0
    private var diagnosticsSnapshot = Gear360Diagnostics()

    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.INVALID)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _selectedDevice = MutableLiveData<DeviceDescription>()
    val selectedDevice: LiveData<DeviceDescription> = _selectedDevice

    private val _gear360Info = MutableLiveData<Gear360Info>()
    val gear360InfoLive: LiveData<Gear360Info> = _gear360Info

    private val _gear360Config = MutableLiveData<Gear360Config>()
    val gear360Config: LiveData<Gear360Config> = _gear360Config

    private val _gear360Status = MutableLiveData<Gear360Status>()
    val gear360Status: LiveData<Gear360Status> = _gear360Status

    private val _diagnostics = MutableLiveData(diagnosticsSnapshot)
    val diagnostics: LiveData<Gear360Diagnostics> = _diagnostics

    val messageLog = MessageLog()
    private val messageHandler = MessageHandler()

    var gear360Info: Gear360Info? = null
        private set

    private val transportListener = object : Gear360ControlTransport.Listener {
        override fun onBackendSelected(name: String) {
            Log.i(TAG_CONNECTION, "Control backend selected: $name")
            updateDiagnostics(backend = name)
        }

        override fun onSamsungFrameworkStatus(status: String, version: String?) {
            Log.i(TAG_CONNECTION, "Samsung framework status=$status version=${version ?: "---"}")
            updateDiagnostics(
                samsungFramework = status,
                samsungFrameworkVersion = version ?: "---"
            )
        }

        override fun onPhysicalTransportState(state: String) {
            Log.i(TAG_CONNECTION, "Physical transport state=$state")
            updateDiagnostics(physicalTransport = state)
            when (state) {
                "CLASSIC_CONNECTING" -> {
                    connectionOrchestrator.mark(Gear360ConnectionPhase.CLASSIC_CONNECTING)
                    updateConnectionState(ConnectionState.BT_CONNECTING)
                }

                "CLASSIC_CONNECTED" -> {
                    connectionOrchestrator.mark(Gear360ConnectionPhase.CLASSIC_CONNECTED)
                    updateConnectionState(ConnectionState.BT_CONNECTED)
                }

                "SAP_NEGOTIATING" -> {
                    connectionOrchestrator.mark(Gear360ConnectionPhase.SAP_HANDSHAKING)
                    updateDiagnostics(
                        sapSocket = "NEGOTIATING",
                        logicalChannel204 = "CLOSED",
                        protocol = "WAITING",
                        camera = "NOT READY"
                    )
                    updateConnectionState(ConnectionState.SAP_CONNECTING)
                }
            }
        }

        override fun onNativeBluetoothDiagnostics(summary: String) {
            Log.i(TAG_CONNECTION, "Native Bluetooth diagnostics: $summary")
            updateDiagnostics(nativeBluetooth = summary)
        }

        override fun onTransportUnavailable(reason: String, error: Throwable?) {
            Log.e(TAG_CONNECTION, reason, error)
            connectionOrchestrator.error(reason)
            updateDiagnostics(lastError = reason, accessoryTransport = "UNAVAILABLE")
            updateConnectionState(ConnectionState.ERROR)
        }

        override fun onAccessoryConnecting() {
            Log.i(TAG_CONNECTION, "Samsung Accessory transport connecting")
            connectionOrchestrator.mark(Gear360ConnectionPhase.CLASSIC_CONNECTING)
            updateDiagnostics(accessoryTransport = "CONNECTING", physicalTransport = "CONNECTING")
            updateConnectionState(ConnectionState.ACCESSORY_CONNECTING)
        }

        override fun onAccessoryConnected() {
            Log.i(TAG_CONNECTION, "Bluetooth/SAP accessory layer connected")
            connectionOrchestrator.mark(Gear360ConnectionPhase.CLASSIC_CONNECTED)
            updateDiagnostics(accessoryTransport = "CONNECTED", physicalTransport = "CONNECTED")
            updateConnectionState(ConnectionState.ACCESSORY_CONNECTED)
        }

        override fun onSapDiscoveryStarted() {
            Log.i(TAG_CONNECTION, "SAP peer discovery started")
            connectionOrchestrator.mark(Gear360ConnectionPhase.DISCOVERING)
            updateDiagnostics(sapPeer = "DISCOVERING")
            updateConnectionState(ConnectionState.SAP_DISCOVERING)
        }

        override fun onSapPeerFound(name: String?, peer: String?, product: String?) {
            Log.i(TAG_CONNECTION, "SAP peer found name=$name peer=$peer product=$product")
            connectionOrchestrator.mark(Gear360ConnectionPhase.FOUND)
            updateDiagnostics(sapPeer = "FOUND ${name.orEmpty()}".trim())
            updateConnectionState(ConnectionState.SAP_PEER_FOUND)
        }

        override fun onSapConnectionRequested(name: String?, peer: String?, product: String?) {
            Log.i(TAG_CONNECTION, "SAP service connection requested name=$name peer=$peer product=$product")
            connectionOrchestrator.mark(Gear360ConnectionPhase.SAP_HANDSHAKING)
            updateDiagnostics(sapPeer = "FOUND ${name.orEmpty()}".trim(), sapSocket = "CONNECTING")
            updateConnectionState(ConnectionState.SAP_CONNECTING)
        }

        override fun onSapSocketConnected(name: String?, peer: String?, product: String?) {
            handleSapSocketConnected(name, peer, product)
        }

        override fun onSapPeerUnavailable() {
            Log.w(TAG_CONNECTION, "SAP peer unavailable")
            updateDiagnostics(sapPeer = "UNAVAILABLE", sapSocket = "DISCONNECTED")
        }

        override fun onSapPeerDiscoveryFailed(result: Int, message: String) {
            Log.e(TAG_CONNECTION, "SAP peer discovery failed result=$result message=$message")
            connectionOrchestrator.error(message)
            updateDiagnostics(sapPeer = "NOT FOUND", lastError = message)
            updateConnectionState(ConnectionState.ERROR)
        }

        override fun onSapConnectionFailed(result: Int, message: String) {
            Log.e(TAG_CONNECTION, "SAP connection failed result=$result message=$message")
            connectionOrchestrator.error(message)
            updateDiagnostics(sapSocket = "DISCONNECTED", lastError = message)
            updateConnectionState(ConnectionState.ERROR)
        }

        override fun onSapError(result: Int, message: String) {
            Log.e(TAG_CONNECTION, "SAP error result=$result message=$message")
            updateDiagnostics(lastError = message)
        }

        override fun onControlChannelConnected(name: String?, peer: String?, product: String?) {
            Log.i(TAG_CONNECTION, "Legacy control channel callback name=$name peer=$peer product=$product")
            handleSapSocketConnected(name, peer, product)
        }

        override fun onDisconnected(reason: String?) {
            Log.i(TAG_CONNECTION, "Disconnected: ${reason ?: "unknown"}")
            onDisconnect()
        }

        override fun onError(reason: String, error: Throwable?) {
            Log.e(TAG_CONNECTION, reason, error)
            connectionOrchestrator.error(reason)
            updateDiagnostics(lastError = reason)
            updateConnectionState(ConnectionState.ERROR)
        }

        override fun onReceive(channelId: Int, data: ByteArray) {
            handleRawReceive(channelId, data)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val bluetoothDevice = bluetoothDeviceFromIntent(intent) ?: return
            val address = AndroidPermissionUtils.getBluetoothDeviceAddress(
                this@Gear360Service,
                bluetoothDevice
            ) ?: return
            val selected = selectedDevice.value ?: return
            if (selected.address != address) return

            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG_BT, "Bluetooth paired: ${selected.name}")
                    connectionOrchestrator.reset()
                    updateDiagnostics(bluetoothBond = "BONDED")
                    SettingsHelper(this@Gear360Service).addPairedDevice(selected)
                    val connectDevice = connectAfterPairDevice
                    connectAfterPairDevice = null
                    if (connectDevice != null) {
                        connectCamera(connectDevice)
                    } else {
                        updateConnectionState(ConnectionState.DISCONNECTED)
                    }
                }

                BluetoothDevice.BOND_NONE -> {
                    val previousState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR
                    )
                    if (previousState == BluetoothDevice.BOND_BONDING) {
                        Log.w(TAG_BT, "Bluetooth pairing failed: ${selected.name}")
                        connectionOrchestrator.error("Bluetooth pairing failed")
                        updateDiagnostics(bluetoothBond = "NOT BONDED", lastError = "Bluetooth pairing failed")
                        connectAfterPairDevice = null
                        updateConnectionState(ConnectionState.ERROR)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG_CONNECTION, "Service created")
        super.onCreate()

        messageSender = MessageSender { channelId, data ->
            val sent = controlTransport.send(channelId, data)
            if (sent) {
                Log.d(TAG_PROTOCOL, "TX ch=$channelId size=${data.size}")
                messageLog.messageSent(channelId, data)
            } else {
                Log.w(TAG_PROTOCOL, "TX failed ch=$channelId; control transport unavailable")
            }
        }

        connectionOrchestrator = Gear360ConnectionOrchestrator()
        registerBondReceiver()
        messageHandler.addMessageListener(this::onMessage)
        controlTransport = Gear360TransportFactory.createDefault(applicationContext, transportListener)
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    override fun onDestroy() {
        Log.i(TAG_CONNECTION, "Service destroyed")
        super.onDestroy()

        mainHandler.removeCallbacksAndMessages(null)
        messageHandler.removeMessageListener(this::onMessage)
        if (::controlTransport.isInitialized) {
            controlTransport.release()
        }
        if (::connectionOrchestrator.isInitialized) {
            connectionOrchestrator.reset()
        }
        if (bondReceiverRegistered) {
            unregisterReceiver(bondReceiver)
            bondReceiverRegistered = false
        }
    }

    fun connectCamera(device: DeviceDescription) {
        connect(device)
    }

    fun disconnectCamera() {
        disconnect()
    }

    fun connectCameraWifi(): Boolean {
        val info = gear360Info
        if (info == null || info.apSSID.isBlank()) {
            Log.w(TAG_WIFI, "Cannot connect Wi-Fi; camera AP properties not available yet")
            return false
        }

        if (!AndroidPermissionUtils.hasNearbyWifiDevicesPermission(this)) {
            Log.w(TAG_WIFI, "Cannot connect Wi-Fi; NEARBY_WIFI_DEVICES permission missing")
            updateConnectionState(ConnectionState.ERROR)
            return false
        }

        Log.i(TAG_WIFI, "Connecting to Gear 360 Wi-Fi ssid=${info.apSSID}")
        updateConnectionState(ConnectionState.WIFI_CONNECTING)
        val session = WifiUtils.connectToWifi(
            applicationContext,
            info.apSSID,
            info.apPassword,
            hidden = false,
            listener = object : WifiUtils.WifiConnectionListener {
                override fun onAvailable(network: Network) {
                    Log.i(TAG_WIFI, "Gear 360 Wi-Fi connected")
                    updateDiagnostics(wifi = "CONNECTED")
                    restoreControlStateAfterWifiAttempt()
                }

                override fun onUnavailable() {
                    Log.w(TAG_WIFI, "Gear 360 Wi-Fi unavailable")
                    if (currentConnectionState == ConnectionState.WIFI_CONNECTING) {
                        updateDiagnostics(wifi = "OFF", lastError = "Gear 360 Wi-Fi unavailable")
                        restoreControlStateAfterWifiAttempt()
                    }
                }

                override fun onLost() {
                    Log.i(TAG_WIFI, "Gear 360 Wi-Fi lost")
                    if (currentConnectionState == ConnectionState.WIFI_AVAILABLE ||
                        currentConnectionState == ConnectionState.DOWNLOADING
                    ) {
                        updateConnectionState(ConnectionState.READY)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    Log.d(TAG_WIFI, "Gear 360 Wi-Fi capabilities=$capabilities")
                }
            }
        )

        if (session == null) {
            restoreControlStateAfterWifiAttempt()
            return false
        }

        return true
    }

    private fun restoreControlStateAfterWifiAttempt() {
        val state = when {
            protocolReceivedConfig -> ConnectionState.READY
            protocolReceivedChannel204 -> ConnectionState.PROTOCOL_SYNCING
            currentConnectionState.hasControlChannel() -> ConnectionState.SAP_SOCKET_CONNECTED
            else -> ConnectionState.DISCONNECTED
        }
        updateConnectionState(state)
    }

    fun connect(device: DeviceDescription) {
        Log.i(TAG_CONNECTION, "Connect requested for ${device.name}")
        if (!connectionOrchestrator.beginConnect(device)) {
            val reason = "Connection already in progress: ${connectionOrchestrator.currentPhase()}"
            updateDiagnostics(lastError = reason)
            Log.w(TAG_CONNECTION, reason)
            return
        }

        updateDiagnostics(bluetoothDevice = device.name)
        selectedDevice.value?.let {
            if (it.address != device.address && ::controlTransport.isInitialized) {
                controlTransport.disconnect(it)
            }
        }

        SettingsHelper(this).setLastConnectedDevice(device)
        updateSelectedDevice(device)

        val btDevice = bluetoothDevice(device)
        val bondState = btDevice?.let { AndroidPermissionUtils.getBondState(this, it) }
        Log.i(TAG_BT, "Bond state for ${device.name}: ${bondStateName(bondState)}")
        if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG_BT, "BOND_BONDED ${device.name}")
            SettingsHelper(this).addPairedDevice(device)
            updateDiagnostics(bluetoothBond = "BONDED")
            updateConnectionState(ConnectionState.BT_BONDED)
        } else {
            Log.i(TAG_BT, "Not bonded; trying public RFCOMM workflow without createBond() loop")
            updateDiagnostics(bluetoothBond = "NOT BONDED")
        }
        updateConnectionState(ConnectionState.BT_CONNECTING)

        if (!::controlTransport.isInitialized) {
            Log.w(TAG_CONNECTION, "Control transport is not initialised yet")
            connectionOrchestrator.error("Control transport is not initialised")
            updateConnectionState(ConnectionState.ERROR)
            return
        }

        controlTransport.connect(device)
    }

    fun disconnect(device: DeviceDescription? = selectedDevice.value) {
        Log.i(TAG_CONNECTION, "Disconnect requested")
        if (::connectionOrchestrator.isInitialized) {
            connectionOrchestrator.mark(Gear360ConnectionPhase.DISCONNECTING)
        }
        if (::controlTransport.isInitialized) {
            controlTransport.disconnect(device)
        }
        onDisconnect()
    }

    fun pairCamera(device: DeviceDescription, connectAfterPair: Boolean = false): Boolean {
        if (!connectionOrchestrator.beginPair(device)) {
            val reason = "Pairing already in progress or retry guard active: ${connectionOrchestrator.currentPhase()}"
            updateDiagnostics(lastError = reason)
            Log.w(TAG_BT, reason)
            return false
        }

        updateSelectedDevice(device)
        SettingsHelper(this).setLastConnectedDevice(device)

        if (!AndroidPermissionUtils.hasBluetoothConnectPermission(this)) {
            Log.w(TAG_BT, "Missing BLUETOOTH_CONNECT permission")
            connectionOrchestrator.error("Missing BLUETOOTH_CONNECT permission")
            updateConnectionState(ConnectionState.ERROR)
            return false
        }

        val bluetoothDevice = bluetoothDevice(device) ?: run {
            Log.w(TAG_BT, "Cannot resolve Bluetooth device ${device.address}")
            connectionOrchestrator.error("Cannot resolve Bluetooth device")
            updateConnectionState(ConnectionState.ERROR)
            return false
        }

        return pairBluetoothDevice(bluetoothDevice, device, connectAfterPair)
    }

    fun takePhoto(): Boolean {
        return capture(CameraMode.PHOTO)
    }

    fun startVideo(): Boolean {
        return capture(CameraMode.VIDEO)
    }

    fun stopVideo(): Boolean {
        if (!ensureReadyForCapture("stop video")) return false

        Log.i(TAG_CAPTURE, "Stop video requested")
        messageSender.sendCaptureStopRequest(CameraMode.VIDEO, CaptureState.RECORDING)
        updateConnectionState(ConnectionState.CAPTURING)
        scheduleCaptureTimeout("stop video")
        return true
    }

    fun setCameraMode(mode: CameraMode) {
        if (!ensureControlChannel("set mode")) return
        Log.i(TAG_CAPTURE, "Set mode requested: $mode")
        messageSender.sendChangeMode(mode)
    }

    fun setLoopingVideoTime(time: LoopingVideoTime) {
        if (!ensureControlChannel("set looping video time")) return
        messageSender.sendChangeLoopingVideoTime(time)
    }

    fun setLedIndicators(active: Boolean) {
        if (!ensureControlChannel("set LED indicators")) return
        val mode = if (active) LedIndicator.LED_ON else LedIndicator.LED_OFF
        messageSender.sendSetLedIndicators(mode)
    }

    fun setTimerTime(time: TimerTime) {
        if (!ensureControlChannel("set timer")) return
        messageSender.sendChangeTimerTimer(time)
    }

    fun setBeepVolume(volume: BeepVolume) {
        if (!ensureControlChannel("set beep volume")) return
        messageSender.sendChangeBeepVolume(volume)
    }

    fun setAutoPowerOffTime(time: AutoPowerOffTime) {
        if (!ensureControlChannel("set auto power off")) return
        messageSender.sendChangePowerOffTime(time)
    }

    fun requestCameraState(): Boolean {
        if (!ensureControlChannel("request camera state")) return false

        Log.i(TAG_PROTOCOL, "Requesting camera state")
        messageSender.sendWidgetInfoRequest()
        return true
    }

    fun requestCapture() {
        val mode = gear360Config.value?.mode ?: return
        capture(mode)
    }

    fun requestCaptureForMode(mode: CameraMode): Boolean {
        return capture(mode)
    }

    fun requestCaptureStop() {
        val mode = gear360Config.value?.mode ?: CameraMode.VIDEO
        val captureState = gear360Status.value?.captureState ?: CaptureState.RECORDING
        if (!ensureReadyForCapture("stop capture")) return

        messageSender.sendCaptureStopRequest(mode, captureState)
        updateConnectionState(ConnectionState.CAPTURING)
        scheduleCaptureTimeout("stop capture")
    }

    fun requestCaptureStopForMode(
        mode: CameraMode,
        captureState: CaptureState = CaptureState.RECORDING
    ): Boolean {
        if (!ensureReadyForCapture("stop capture")) return false

        messageSender.sendCaptureStopRequest(mode, captureState)
        updateConnectionState(ConnectionState.CAPTURING)
        scheduleCaptureTimeout("stop capture")
        return true
    }

    fun requestLiveView() {
        if (!ensureControlChannel("request live view")) return
        messageSender.sendLiveViewRequest()
    }

    private fun capture(mode: CameraMode): Boolean {
        if (!ensureReadyForCapture("capture $mode")) return false

        Log.i(TAG_CAPTURE, "Capture requested mode=$mode")
        val currentMode = gear360Config.value?.mode
        if (currentMode != null && currentMode != mode) {
            Log.i(TAG_CAPTURE, "Changing mode from $currentMode to $mode before capture")
            messageSender.sendChangeMode(mode)
            mainHandler.postDelayed(
                { sendCaptureRequest(mode) },
                CAPTURE_MODE_CHANGE_DELAY_MS
            )
        } else {
            sendCaptureRequest(mode)
        }

        return true
    }

    private fun sendCaptureRequest(mode: CameraMode) {
        if (!ensureControlChannel("send capture request")) return

        Log.i(TAG_CAPTURE, "Sending capture request mode=$mode")
        messageSender.sendCaptureRequest(mode)
        updateConnectionState(ConnectionState.CAPTURING)
        scheduleCaptureTimeout("capture $mode")
    }

    private fun scheduleCaptureTimeout(operation: String) {
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.w(TAG_CAPTURE, "Timeout waiting for $operation response")
            if (currentConnectionState == ConnectionState.CAPTURING) {
                updateConnectionState(ConnectionState.ERROR)
            }
        }
        captureTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CAPTURE_COMMAND_TIMEOUT_MS)
    }

    private fun clearCaptureTimeout() {
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null
    }

    private fun ensureControlChannel(action: String): Boolean {
        val state = connectionState.value ?: ConnectionState.INVALID
        if (state.hasControlChannel()) return true

        Log.w(TAG_CONNECTION, "Cannot $action; control channel not connected. state=$state")
        return false
    }

    private fun ensureReadyForCapture(action: String): Boolean {
        val state = connectionState.value ?: ConnectionState.INVALID
        if (state.isReadyForCapture()) return true

        Log.w(TAG_CAPTURE, "Cannot $action; camera not ready. state=$state")
        return false
    }

    private fun handleSapSocketConnected(name: String?, peer: String?, product: String?) {
        if (currentConnectionState == ConnectionState.READY ||
            currentConnectionState == ConnectionState.PROTOCOL_SYNCING
        ) {
            return
        }

        Log.i(TAG_CONNECTION, "SAP socket connected name=$name peer=$peer product=$product")
        connectionOrchestrator.mark(Gear360ConnectionPhase.SAP_CONNECTED)
        resetProtocolSync()
        updateDiagnostics(
            sapPeer = "FOUND ${name.orEmpty()}".trim(),
            sapSocket = "CONNECTED",
            logicalChannel204 = "WAITING",
            channel204Rx = false,
            rx204Count = 0,
            protocol = "SYNCING",
            camera = "NOT READY"
        )
        updateConnectionState(ConnectionState.SAP_SOCKET_CONNECTED)
        connectionOrchestrator.mark(Gear360ConnectionPhase.PROTOCOL_SYNCING)
        updateConnectionState(ConnectionState.PROTOCOL_SYNCING)
        scheduleProtocolSyncTimeout()
        scheduleProtocolSafeReadRequest()
    }

    private fun handleRawReceive(channelId: Int, data: ByteArray) {
        Log.d(TAG_PROTOCOL, "RX ch=$channelId size=${data.size}")
        if (channelId == PRIMARY_JSON_CHANNEL) {
            protocolReceivedChannel204 = true
            protocolChannel204RxCount += 1
            updateDiagnostics(
                channel204Rx = true,
                logicalChannel204 = "OPEN",
                rx204Count = protocolChannel204RxCount,
                lastRx = extractMsgId(data) ?: "channel=204 len=${data.size}",
                protocol = "SYNCING"
            )
        }

        messageLog.messageReceived(channelId, data)
        messageHandler.onReceive(channelId, data)
    }

    private fun scheduleProtocolSyncTimeout() {
        protocolSyncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (protocolReceivedConfig || currentConnectionState == ConnectionState.READY) {
                return@Runnable
            }

            val message = if (!protocolReceivedChannel204) {
                "SAP socket established but Gear360 protocol silent"
            } else {
                "Gear360 protocol active but initial configuration not received"
            }
            Log.e(TAG_PROTOCOL, message)
            updateDiagnostics(protocol = "ERROR", camera = "NOT READY", lastError = message)
            updateConnectionState(ConnectionState.ERROR)
        }
        protocolSyncTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, PROTOCOL_SYNC_TIMEOUT_MS)
    }

    private fun scheduleProtocolSafeReadRequest() {
        protocolSafeReadRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (protocolReceivedChannel204 || currentConnectionState != ConnectionState.PROTOCOL_SYNCING) {
                return@Runnable
            }

            Log.i(TAG_PROTOCOL, "Protocol silent; sending safe widget-info request")
            requestCameraState()
        }
        protocolSafeReadRunnable = runnable
        mainHandler.postDelayed(runnable, PROTOCOL_SAFE_READ_DELAY_MS)
    }

    private fun resetProtocolSync() {
        protocolReceivedChannel204 = false
        protocolReceivedConfig = false
        protocolChannel204RxCount = 0
        protocolSyncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        protocolSafeReadRunnable?.let { mainHandler.removeCallbacks(it) }
        protocolSyncTimeoutRunnable = null
        protocolSafeReadRunnable = null
    }

    private fun clearProtocolSyncTimeout() {
        protocolSyncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        protocolSafeReadRunnable?.let { mainHandler.removeCallbacks(it) }
        protocolSyncTimeoutRunnable = null
        protocolSafeReadRunnable = null
    }

    private fun extractMsgId(data: ByteArray): String? {
        val text = try {
            data.decodeToString()
        } catch (e: Exception) {
            return null
        }

        return Regex(""""msgId"\s*:\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun onDisconnect() {
        clearCaptureTimeout()
        resetProtocolSync()
        updateDiagnostics(
            accessoryTransport = "DISCONNECTED",
            physicalTransport = "DISCONNECTED",
            sapPeer = "NOT FOUND",
            sapSocket = "DISCONNECTED",
            logicalChannel204 = "CLOSED",
            channel204Rx = false,
            rx204Count = 0,
            protocol = "DISCONNECTED",
            camera = "NOT READY"
        )
        WifiUtils.disconnectWifi(applicationContext)
        if (::connectionOrchestrator.isInitialized) {
            connectionOrchestrator.reset()
        }
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    @SuppressLint("MissingPermission")
    private fun pairBluetoothDevice(
        bluetoothDevice: BluetoothDevice,
        device: DeviceDescription,
        connectAfterPair: Boolean
    ): Boolean {
        val bondState = AndroidPermissionUtils.getBondState(this, bluetoothDevice)
            ?: run {
                connectionOrchestrator.error("Cannot read Bluetooth bond state")
                updateDiagnostics(lastError = "Cannot read Bluetooth bond state")
                updateConnectionState(ConnectionState.ERROR)
                return false
            }
        Log.i(TAG_BT, "Pair request bond state for ${device.name}: ${bondStateName(bondState)}")

        return when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG_BT, "Already paired: ${device.name}")
                SettingsHelper(this).addPairedDevice(device)
                connectionOrchestrator.reset()
                updateDiagnostics(bluetoothBond = "BONDED")
                updateConnectionState(ConnectionState.BT_BONDED)
                if (connectAfterPair) connectCamera(device)
                true
            }

            BluetoothDevice.BOND_BONDING -> {
                Log.i(TAG_BT, "Pairing already in progress: ${device.name}")
                updateDiagnostics(bluetoothBond = "PAIRING")
                connectAfterPairDevice = if (connectAfterPair) device else null
                updateConnectionState(ConnectionState.PAIRING)
                true
            }

            else -> {
                connectAfterPairDevice = if (connectAfterPair) device else null
                updateDiagnostics(bluetoothBond = "PAIRING")
                updateConnectionState(ConnectionState.PAIRING)
                val started = try {
                    bluetoothDevice.createBond()
                } catch (e: SecurityException) {
                    Log.e(TAG_BT, "Missing permission while pairing", e)
                    false
                } catch (e: RuntimeException) {
                    Log.e(TAG_BT, "Bluetooth pairing failed to start", e)
                    false
                }

                if (!started) {
                    connectAfterPairDevice = null
                    connectionOrchestrator.error("Bluetooth pairing failed to start")
                    updateConnectionState(ConnectionState.ERROR)
                }

                started
            }
        }
    }

    private fun bondStateName(state: Int?): String {
        return when (state) {
            BluetoothDevice.BOND_NONE -> "BOND_NONE($state)"
            BluetoothDevice.BOND_BONDING -> "BOND_BONDING($state)"
            BluetoothDevice.BOND_BONDED -> "BOND_BONDED($state)"
            null -> "UNKNOWN(null)"
            else -> "UNKNOWN($state)"
        }
    }

    private fun onMessage(message: BTMessage2) {
        updateDiagnostics(lastRx = protocolMessageName(message), protocol = "SYNCING")
        when (message) {
            is BTDateTimeRequest -> {
                messageSender.sendDateTimeResponse()
            }

            is BTWidgetInfoRequest -> {
                messageSender.sendWidgetInfoResponse(lastKnownLocation())
                messageSender.sendWidgetInfoRequest()
            }

            is BTWidgetInfoResponseCamera -> {
                val status = Gear360Status(
                    message.battery,
                    message.batteryState,
                    message.totalStorage,
                    message.usedStorage,
                    message.freeStorage,
                    message.recordState,
                    message.captureState,
                    message.autoPowerOff,
                    message.recordableTime,
                    message.capturableCount
                )
                updateGear360Status(status)
                updateStateFromCameraStatus(status)
                messageSender.sendPhoneInfo(WifiUtils.getMacAddress(applicationContext))
            }

            is BTCameraInfoMessage -> {
                updateGear360Info(
                    Gear360Info(
                        message.modelName,
                        message.modelVersion,
                        message.channel,
                        message.wifiDirectMac,
                        message.softApSsid,
                        message.softApPassword,
                        message.boardRevision,
                        message.serialNumber,
                        message.uniqueNumber,
                        message.wifiMac,
                        message.bluetoothMac,
                        message.btFotaTestUrl,
                        message.firmwareType
                    )
                )
            }

            is BTCameraConfigMessage -> {
                protocolReceivedConfig = true
                clearProtocolSyncTimeout()
                updateGear360Config(
                    Gear360Config(
                        mode = CameraMode.fromString(message.mode),
                        timer = TimerTime.fromString(message.timer),
                        beep = BeepVolume.fromString(message.beep),
                        led = LedIndicator.fromString(message.ledIndicator),
                        autoPowerOffTime = AutoPowerOffTime.fromString(message.autoPowerOff),
                        loopingVideoTime = LoopingVideoTime.fromString(message.loopingVideoTime),
                    )
                )

                if (currentConnectionState != ConnectionState.CAPTURING &&
                    currentConnectionState != ConnectionState.RECORDING
                ) {
                    updateDiagnostics(protocol = "READY", camera = "READY")
                    Log.i(TAG_CONNECTION, "READY config-info received")
                    connectionOrchestrator.mark(Gear360ConnectionPhase.READY)
                    updateConnectionState(ConnectionState.READY)
                }
            }

            is BTRemoteShotResponse -> {
                clearCaptureTimeout()
                val captureState = when (CaptureCommand.fromString(message.description)) {
                    CaptureCommand.CAPTURE -> CaptureState.NONE
                    CaptureCommand.RECORD -> CaptureState.RECORDING
                    CaptureCommand.RECORD_STOP -> CaptureState.NONE
                    CaptureCommand.TIMER -> CaptureState.TIMER
                    CaptureCommand.TIMER_STOP -> CaptureState.NONE
                    null -> null
                }

                updateGear360Status(
                    Gear360Status(
                        capturableCount = message.capturableCount,
                        recordableTime = message.recordableTime,
                        captureState = captureState,
                        recordState = captureState,
                    )
                )

                when (captureState) {
                    CaptureState.RECORDING -> updateConnectionState(ConnectionState.RECORDING)
                    CaptureState.TIMER, CaptureState.CAPTURING -> {
                        updateConnectionState(ConnectionState.CAPTURING)
                    }
                    else -> updateConnectionState(ConnectionState.READY)
                }
            }

            is BTCommandRequest -> {
                if (message.action is BTCommandActionConfig) {
                    updateGear360Config(message.action.config)
                }
            }
        }
    }

    private fun updateStateFromCameraStatus(status: Gear360Status) {
        val recording = status.recordState == CaptureState.RECORDING ||
            status.captureState == CaptureState.RECORDING
        if (recording && protocolReceivedConfig) {
            updateConnectionState(ConnectionState.RECORDING)
        } else if (protocolReceivedConfig && currentConnectionState.hasControlChannel()) {
            updateConnectionState(ConnectionState.READY)
        }
    }

    private fun protocolMessageName(message: BTMessage2): String {
        return when (message) {
            is BTDateTimeRequest -> "date-time-req"
            is BTWidgetInfoRequest -> "widget-info-req"
            is BTWidgetInfoResponseCamera -> "widget-info-rsp"
            is BTCameraInfoMessage -> "device-info"
            is BTCameraConfigMessage -> "config-info"
            is BTRemoteShotResponse -> "shot-rsp"
            is BTCommandRequest -> "cmd-req"
            else -> message.javaClass.simpleName
        }
    }

    private fun lastKnownLocation(): Location? {
        val service = getSystemService<LocationManager>() ?: return null
        val provider = service.getBestProvider(Criteria(), true) ?: return null

        return try {
            service.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            Log.w(TAG_PROTOCOL, "Location permission missing for widget response", e)
            null
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        Log.i(TAG_CONNECTION, "state=$state")
        currentConnectionState = state
        updateProtocolDiagnosticsForState(state)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _connectionState.value = state
        } else {
            _connectionState.postValue(state)
        }
    }

    private fun updateProtocolDiagnosticsForState(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED, ConnectionState.INVALID -> updateDiagnostics(
                accessoryTransport = "DISCONNECTED",
                physicalTransport = "DISCONNECTED",
                sapPeer = "NOT FOUND",
                sapSocket = "DISCONNECTED",
                logicalChannel204 = "CLOSED",
                protocol = "DISCONNECTED",
                camera = "NOT READY"
            )
            ConnectionState.ACCESSORY_CONNECTING -> updateDiagnostics(
                accessoryTransport = "CONNECTING",
                physicalTransport = "CONNECTING",
                camera = "NOT READY"
            )
            ConnectionState.ACCESSORY_CONNECTED -> updateDiagnostics(
                accessoryTransport = "CONNECTED",
                physicalTransport = "CONNECTED",
                camera = "NOT READY"
            )
            ConnectionState.SAP_DISCOVERING -> updateDiagnostics(
                sapPeer = "DISCOVERING",
                camera = "NOT READY"
            )
            ConnectionState.SAP_PEER_FOUND -> updateDiagnostics(
                sapPeer = "FOUND",
                camera = "NOT READY"
            )
            ConnectionState.SAP_CONNECTING -> updateDiagnostics(
                sapSocket = "CONNECTING",
                camera = "NOT READY"
            )
            ConnectionState.SAP_SOCKET_CONNECTED -> updateDiagnostics(
                sapSocket = "CONNECTED",
                logicalChannel204 = "WAITING",
                protocol = "WAITING",
                camera = "NOT READY"
            )
            ConnectionState.PROTOCOL_SYNCING -> updateDiagnostics(
                protocol = "SYNCING",
                camera = "NOT READY"
            )
            ConnectionState.READY, ConnectionState.CONNECTED -> updateDiagnostics(
                protocol = "READY",
                camera = "READY"
            )
            ConnectionState.RECORDING -> updateDiagnostics(
                protocol = "READY",
                camera = "RECORDING"
            )
            ConnectionState.ERROR -> updateDiagnostics(camera = "NOT READY")
            else -> Unit
        }
    }

    private fun updateDiagnostics(
        bluetoothDevice: String? = null,
        bluetoothBond: String? = null,
        physicalTransport: String? = null,
        backend: String? = null,
        samsungFramework: String? = null,
        samsungFrameworkVersion: String? = null,
        accessoryTransport: String? = null,
        sapPeer: String? = null,
        sapSocket: String? = null,
        logicalChannel204: String? = null,
        channel204Rx: Boolean? = null,
        rx204Count: Int? = null,
        lastRx: String? = null,
        protocol: String? = null,
        wifi: String? = null,
        camera: String? = null,
        nativeBluetooth: String? = null,
        lastError: String? = null
    ) {
        diagnosticsSnapshot = diagnosticsSnapshot.copy(
            bluetoothDevice = bluetoothDevice ?: diagnosticsSnapshot.bluetoothDevice,
            bluetoothBond = bluetoothBond ?: diagnosticsSnapshot.bluetoothBond,
            physicalTransport = physicalTransport ?: diagnosticsSnapshot.physicalTransport,
            backend = backend ?: diagnosticsSnapshot.backend,
            samsungFramework = samsungFramework ?: diagnosticsSnapshot.samsungFramework,
            samsungFrameworkVersion = samsungFrameworkVersion ?: diagnosticsSnapshot.samsungFrameworkVersion,
            accessoryTransport = accessoryTransport ?: diagnosticsSnapshot.accessoryTransport,
            sapPeer = sapPeer ?: diagnosticsSnapshot.sapPeer,
            sapSocket = sapSocket ?: diagnosticsSnapshot.sapSocket,
            logicalChannel204 = logicalChannel204 ?: diagnosticsSnapshot.logicalChannel204,
            channel204Rx = channel204Rx ?: diagnosticsSnapshot.channel204Rx,
            rx204Count = rx204Count ?: diagnosticsSnapshot.rx204Count,
            lastRx = lastRx ?: diagnosticsSnapshot.lastRx,
            protocol = protocol ?: diagnosticsSnapshot.protocol,
            wifi = wifi ?: diagnosticsSnapshot.wifi,
            camera = camera ?: diagnosticsSnapshot.camera,
            nativeBluetooth = nativeBluetooth ?: diagnosticsSnapshot.nativeBluetooth,
            lastError = lastError ?: diagnosticsSnapshot.lastError
        )

        if (Looper.myLooper() == Looper.getMainLooper()) {
            _diagnostics.value = diagnosticsSnapshot
        } else {
            _diagnostics.postValue(diagnosticsSnapshot)
        }
    }

    private fun updateSelectedDevice(device: DeviceDescription?) {
        Log.i(TAG_CONNECTION, "selectedDevice=$device")
        updateDiagnostics(bluetoothDevice = device?.name ?: "---")
        _selectedDevice.postValue(device)
    }

    private fun updateGear360Info(info: Gear360Info) {
        val safeInfo = info.copy(apPassword = "***")
        Log.i(TAG_CONNECTION, "cameraInfo=$safeInfo")
        gear360Info = info
        _gear360Info.postValue(info)
        updateDiagnostics(wifi = "OFF")
    }

    private fun updateGear360Config(config: Gear360Config) {
        Log.d(TAG_PROTOCOL, "config=$config")
        val old = gear360Config.value ?: Gear360Config()
        _gear360Config.postValue(old.merge(config))
    }

    private fun updateGear360Status(status: Gear360Status) {
        Log.d(TAG_PROTOCOL, "status=$status")
        val old = gear360Status.value ?: Gear360Status()
        _gear360Status.postValue(old.merge(status))
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return

        ContextCompat.registerReceiver(
            this,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bondReceiverRegistered = true
    }

    private fun bluetoothDevice(device: DeviceDescription): BluetoothDevice? {
        val adapter = AndroidPermissionUtils.bluetoothAdapter(this) ?: return null
        return try {
            adapter.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun bluetoothDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG_CONNECTION, "onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG_CONNECTION, "onRebind")
        super.onRebind(intent)
    }

    inner class LocalBinder : Binder() {
        fun getService(): Gear360Service {
            return this@Gear360Service
        }
    }
}
