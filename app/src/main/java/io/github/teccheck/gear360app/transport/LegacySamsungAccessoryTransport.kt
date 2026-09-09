package io.github.teccheck.gear360app.transport

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.samsung.android.sdk.SsdkUnsupportedException
import com.samsung.android.sdk.accessory.SAAgentV2
import com.samsung.android.sdk.accessorymanager.SamAccessoryManager
import com.samsung.android.sdk.accessorymanager.SamDevice
import io.github.teccheck.gear360app.bluetooth.BTMProviderService
import io.github.teccheck.gear360app.utils.DeviceDescription
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG_SAM = "LEGACY-SAM"
private const val TAG_SAP = "LEGACY-SAP"
private const val TAG_TX = "LEGACY-TX"
private const val SA_TRANSPORT_TYPE = SamAccessoryManager.TRANSPORT_BT
private const val SOCKET_RETRY_DELAY_MS = 1_500L
private const val MAX_SOCKET_CONNECT_ATTEMPTS = 3

class LegacySamsungAccessoryTransport(
    context: Context,
    private val listener: Gear360ControlTransport.Listener
) : Gear360ControlTransport {
    private val appContext = context.applicationContext

    private var samAccessoryManager: SamAccessoryManager? = null
    private var btmProviderService: BTMProviderService? = null
    private var pendingConnectDevice: DeviceDescription? = null
    private var activeConnectDevice: DeviceDescription? = null
    private var accessoryConnected = false
    private var unavailable = false
    private var socketConnectAttempts = 0
    private val connectionWorker: ExecutorService = Executors.newSingleThreadExecutor()

    private val samListener = object : SamAccessoryManager.AccessoryEventListener {
        override fun onAccessoryConnected(device: SamDevice) {
            Log.i(TAG_SAM, "Accessory connected: $device")
            accessoryConnected = true
            socketConnectAttempts = 0
            listener.onPhysicalTransportState("CONNECTED")
            listener.onAccessoryConnected()
            connectBTMProviderService()
        }

        override fun onAccessoryDisconnected(device: SamDevice, reason: Int) {
            Log.i(TAG_SAM, "Accessory disconnected: $device reason=${managerResultName(reason)}($reason)")
            accessoryConnected = false
            listener.onPhysicalTransportState("DISCONNECTED")
            listener.onDisconnected("Samsung Accessory disconnected: ${managerResultName(reason)}($reason)")
        }

        override fun onError(device: SamDevice?, reason: Int) {
            val namedReason = "${managerResultName(reason)}($reason)"
            Log.w(TAG_SAM, "Accessory error device=$device reason=$namedReason")
            if (reason == SamAccessoryManager.ERROR_ACCESSORY_ALREADY_CONNECTED) {
                accessoryConnected = true
                listener.onPhysicalTransportState("CONNECTED")
                listener.onAccessoryConnected()
                connectBTMProviderService()
            } else if (isRetryableSocketError(reason) && retryLegacyConnection(reason)) {
                return
            } else {
                listener.onError("Samsung Accessory error: $namedReason")
            }
        }

        override fun onAccountLoggedIn(device: SamDevice) {}
        override fun onAccountLoggedOut(device: SamDevice) {}
    }

    private val btmStatusCallback = object : BTMProviderService.StatusCallback {
        override fun onConnectDevice(name: String?, peer: String?, product: String?) {
            Log.i(TAG_SAP, "Legacy control-channel callback name=$name peer=$peer product=$product")
        }

        override fun onError(result: Int) {
            Log.w(TAG_SAP, "BTM provider error: $result")
            listener.onError("Samsung Accessory socket error: $result")
        }

        override fun onReceive(channelId: Int, data: ByteArray?) {
            if (data != null) {
                listener.onReceive(channelId, data)
            }
        }

        override fun onServiceDisconnection() {
            Log.i(TAG_SAP, "BTM provider disconnected")
            accessoryConnected = false
            listener.onDisconnected("Samsung Accessory service disconnected")
        }

        override fun onSapDiscoveryStarted() {
            listener.onSapDiscoveryStarted()
        }

        override fun onSapPeerFound(name: String?, peer: String?, product: String?) {
            listener.onSapPeerFound(name, peer, product)
        }

        override fun onSapPeerUnavailable() {
            listener.onSapPeerUnavailable()
        }

        override fun onSapPeerDiscoveryFailed(result: Int, message: String) {
            listener.onSapPeerDiscoveryFailed(result, message)
        }

        override fun onSapConnectionRequested(name: String?, peer: String?, product: String?) {
            listener.onSapConnectionRequested(name, peer, product)
        }

        override fun onSapConnectionFailed(result: Int, message: String) {
            listener.onSapConnectionFailed(result, message)
        }

        override fun onSapSocketConnected(name: String?, peer: String?, product: String?) {
            listener.onSapSocketConnected(name, peer, product)
        }

        override fun onSapError(result: Int, message: String) {
            listener.onSapError(result, message)
        }
    }

    init {
        listener.onBackendSelected("SAMSUNG LEGACY")
        initialise()
    }

    override fun connect(device: DeviceDescription) {
        if (unavailable) {
            listener.onTransportUnavailable("Samsung Accessory framework unavailable")
            return
        }

        val manager = samAccessoryManager
        if (manager == null) {
            Log.i(TAG_SAM, "Manager not ready; queueing connection for ${device.name}")
            pendingConnectDevice = device
            return
        }

        pendingConnectDevice = null
        activeConnectDevice = device
        socketConnectAttempts = 0
        startAccessoryConnection(device, manager, retryDelayMs = 0L)
    }

    override fun disconnect(device: DeviceDescription?) {
        pendingConnectDevice = null
        activeConnectDevice = null
        try {
            btmProviderService?.closeConnection()
        } catch (e: RuntimeException) {
            Log.w(TAG_SAP, "Failed to close Samsung Accessory socket during disconnect", e)
        }

        val address = device?.address
        if (address != null) {
            try {
                samAccessoryManager?.disconnect(address, SA_TRANSPORT_TYPE)
            } catch (e: Exception) {
                Log.w(TAG_SAM, "Failed to disconnect Samsung Accessory transport", e)
            }
        }

        accessoryConnected = false
        listener.onPhysicalTransportState("DISCONNECTED")
    }

    override fun send(channelId: Int, data: ByteArray): Boolean {
        val provider = btmProviderService
        if (provider == null || !provider.isSocketConnected) {
            Log.w(TAG_TX, "Cannot send; SAP socket is not connected")
            return false
        }

        provider.send(channelId, data)
        return true
    }

    override fun release() {
        pendingConnectDevice = null
        activeConnectDevice = null
        try {
            btmProviderService?.releaseProvider()
        } catch (e: RuntimeException) {
            Log.w(TAG_SAP, "Failed to release Samsung Accessory provider", e)
        }
        btmProviderService = null

        try {
            samAccessoryManager?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG_SAM, "Failed to release Samsung Accessory manager", e)
        }
        samAccessoryManager = null
        connectionWorker.shutdownNow()
    }

    private fun initialise() {
        val frameworkStatus = SamsungAccessoryFramework.inspect(appContext)
        Log.i(
            TAG_SAM,
            "framework package present ${frameworkStatus.presentText} version=${frameworkStatus.version}"
                + " permission=${frameworkStatus.permissionText}"
        )
        listener.onSamsungFrameworkStatus(frameworkStatus.diagnosticStatus, frameworkStatus.version)

        val handlerThread = HandlerThread("$TAG_SAM init")
        handlerThread.start()

        val handler = Handler(handlerThread.looper ?: return)
        handler.post {
            try {
                samAccessoryManager = SamAccessoryManager.getInstance(appContext, samListener)
                Log.i(TAG_SAM, "bind manager SUCCESS")
            } catch (e: SsdkUnsupportedException) {
                unavailable = true
                Log.e(TAG_SAM, "bind manager FAIL: Samsung Accessory SDK unsupported", e)
                postUnavailable("Samsung Accessory SDK unsupported", e)
            } catch (e: Exception) {
                unavailable = true
                Log.e(TAG_SAM, "bind manager FAIL: Samsung Accessory manager unavailable", e)
                postUnavailable("Samsung Accessory manager unavailable", e)
            }

            Handler(Looper.getMainLooper()).post {
                if (!unavailable) {
                    initBTMProviderService()
                }
            }

            handlerThread.quitSafely()
        }
    }

    private fun initBTMProviderService() {
        val requestAgentCallback = object : SAAgentV2.RequestAgentCallback {
            override fun onAgentAvailable(agent: SAAgentV2) {
                Log.i(TAG_SAP, "SA agent available: $agent")
                btmProviderService = agent as BTMProviderService
                btmProviderService?.setup(btmStatusCallback)

                if (accessoryConnected) {
                    connectBTMProviderService()
                }

                pendingConnectDevice?.let {
                    pendingConnectDevice = null
                    connect(it)
                }
            }

            override fun onError(errorCode: Int, message: String) {
                unavailable = true
                Log.e(TAG_SAP, "SA agent error code=$errorCode message=$message")
                listener.onTransportUnavailable("Samsung Accessory agent error: $errorCode $message")
            }
        }

        try {
            SAAgentV2.requestAgent(
                appContext,
                BTMProviderService::class.java.name,
                requestAgentCallback
            )
        } catch (e: Exception) {
            unavailable = true
            Log.e(TAG_SAP, "Failed to request Samsung Accessory agent", e)
            listener.onTransportUnavailable("Samsung Accessory agent request failed", e)
        }
    }

    private fun connectBTMProviderService() {
        val provider = btmProviderService
        if (provider == null) {
            Log.i(TAG_SAP, "BTM provider is not ready yet")
            return
        }

        Log.i(TAG_SAP, "findPeerAgents")
        provider.findSaPeers()
    }

    private fun startAccessoryConnection(
        device: DeviceDescription,
        manager: SamAccessoryManager,
        retryDelayMs: Long
    ) {
        connectionWorker.execute {
            if (retryDelayMs > 0) {
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }

            val stillRequested = activeConnectDevice?.address == device.address &&
                samAccessoryManager === manager
            if (!stillRequested) {
                Log.i(TAG_SAM, "Skipping cancelled Samsung Accessory connection for ${device.name}")
                return@execute
            }

            socketConnectAttempts += 1
            try {
                Log.i(
                    TAG_SAM,
                    "Connecting via Samsung Accessory BT to ${device.name} " +
                        "assistMode=DEFAULT attempt=$socketConnectAttempts/$MAX_SOCKET_CONNECT_ATTEMPTS"
                )
                listener.onPhysicalTransportState("CONNECTING")
                listener.onAccessoryConnecting()
                manager.connect(
                    device.address,
                    SA_TRANSPORT_TYPE,
                    SamAccessoryManager.ASSISTMODE_DEFAULT
                )
            } catch (e: Exception) {
                Log.e(TAG_SAM, "Failed to start Samsung Accessory connection", e)
                if (!retryLegacyConnection(SamAccessoryManager.ERROR_SOCKET_CONNECT_FAILED)) {
                    listener.onError("Samsung Accessory connect failed", e)
                }
            }
        }
    }

    @Synchronized
    private fun retryLegacyConnection(reason: Int): Boolean {
        val device = activeConnectDevice ?: return false
        val manager = samAccessoryManager ?: return false
        if (socketConnectAttempts >= MAX_SOCKET_CONNECT_ATTEMPTS) {
            Log.e(
                TAG_SAM,
                "Samsung Accessory socket retries exhausted " +
                    "reason=${managerResultName(reason)}($reason) attempts=$socketConnectAttempts"
            )
            return false
        }

        Log.w(
            TAG_SAM,
            "Samsung Accessory socket error=${managerResultName(reason)}($reason); " +
                "scheduling retry ${socketConnectAttempts + 1}/$MAX_SOCKET_CONNECT_ATTEMPTS"
        )
        startAccessoryConnection(device, manager, SOCKET_RETRY_DELAY_MS)
        return true
    }

    private fun isRetryableSocketError(reason: Int): Boolean {
        return reason == SamAccessoryManager.ERROR_SOCKET_CREATION_FAILED ||
            reason == SamAccessoryManager.ERROR_SOCKET_CONNECT_FAILED ||
            reason == SamAccessoryManager.ERROR_SOCKET_CONNECT_TIMEOUT
    }

    private fun managerResultName(reason: Int): String {
        return when (reason) {
            SamAccessoryManager.ACCESSORY_DISCONNECTED_NORMAL -> "ACCESSORY_DISCONNECTED_NORMAL"
            SamAccessoryManager.ACCESSORY_DISCONNECTED_NETWORK_FAILURE ->
                "ACCESSORY_DISCONNECTED_NETWORK_FAILURE"
            SamAccessoryManager.ACCESSORY_DISCONNECTED_PACKET_CORRUPTION ->
                "ACCESSORY_DISCONNECTED_PACKET_CORRUPTION"
            SamAccessoryManager.ERROR_ACCESSORY_ALREADY_CONNECTED ->
                "ERROR_ACCESSORY_ALREADY_CONNECTED"
            SamAccessoryManager.ERROR_ACCESSORY_FRAMEWORK_INCOMPATIBLE ->
                "ERROR_ACCESSORY_FRAMEWORK_INCOMPATIBLE"
            SamAccessoryManager.ERROR_ACCESSORY_NOT_CONNECTED -> "ERROR_ACCESSORY_NOT_CONNECTED"
            SamAccessoryManager.ERROR_ACCESSORY_NOT_PAIRED -> "ERROR_ACCESSORY_NOT_PAIRED"
            SamAccessoryManager.ERROR_ANOTHER_TRANSPORT_TYPE_STILL_ACTIVE ->
                "ERROR_ANOTHER_TRANSPORT_TYPE_STILL_ACTIVE"
            SamAccessoryManager.ERROR_ASSISTMODE_CONNECTIVITY_NOT_SUPPORTED ->
                "ERROR_ASSISTMODE_CONNECTIVITY_NOT_SUPPORTED"
            SamAccessoryManager.ERROR_ASSISTMODE_INVALID_MODE -> "ERROR_ASSISTMODE_INVALID_MODE"
            SamAccessoryManager.ERROR_CATEGORY_NOT_ALLOWED -> "ERROR_CATEGORY_NOT_ALLOWED"
            SamAccessoryManager.ERROR_EXCEED_MAX_CONNECTIONS -> "ERROR_EXCEED_MAX_CONNECTIONS"
            SamAccessoryManager.ERROR_FATAL -> "ERROR_FATAL"
            SamAccessoryManager.ERROR_ONGOING_CONNECTION -> "ERROR_ONGOING_CONNECTION"
            SamAccessoryManager.ERROR_OPERATION_IN_PROGRESS -> "ERROR_OPERATION_IN_PROGRESS"
            SamAccessoryManager.ERROR_SOCKET_CLOSE_FAILED -> "ERROR_SOCKET_CLOSE_FAILED"
            SamAccessoryManager.ERROR_SOCKET_CONNECT_FAILED -> "ERROR_SOCKET_CONNECT_FAILED"
            SamAccessoryManager.ERROR_SOCKET_CONNECT_TIMEOUT -> "ERROR_SOCKET_CONNECT_TIMEOUT"
            SamAccessoryManager.ERROR_SOCKET_CREATION_FAILED -> "ERROR_SOCKET_CREATION_FAILED"
            SamAccessoryManager.ERROR_SOCKET_READ_WRITE_FAILED -> "ERROR_SOCKET_READ_WRITE_FAILED"
            else -> "UNKNOWN"
        }
    }

    private fun postUnavailable(reason: String, error: Throwable) {
        Handler(Looper.getMainLooper()).post {
            listener.onTransportUnavailable(reason, error)
        }
    }
}
