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

private const val TAG_SAM = "LEGACY-SAM"
private const val TAG_SAP = "LEGACY-SAP"
private const val TAG_TX = "LEGACY-TX"
private const val SA_TRANSPORT_TYPE = SamAccessoryManager.TRANSPORT_BT

class LegacySamsungAccessoryTransport(
    context: Context,
    private val listener: Gear360ControlTransport.Listener
) : Gear360ControlTransport {
    private val appContext = context.applicationContext

    private var samAccessoryManager: SamAccessoryManager? = null
    private var btmProviderService: BTMProviderService? = null
    private var pendingConnectDevice: DeviceDescription? = null
    private var accessoryConnected = false
    private var unavailable = false

    private val samListener = object : SamAccessoryManager.AccessoryEventListener {
        override fun onAccessoryConnected(device: SamDevice) {
            Log.i(TAG_SAM, "Accessory connected: $device")
            accessoryConnected = true
            listener.onPhysicalTransportState("CONNECTED")
            listener.onAccessoryConnected()
            connectBTMProviderService()
        }

        override fun onAccessoryDisconnected(device: SamDevice, reason: Int) {
            Log.i(TAG_SAM, "Accessory disconnected: $device reason=$reason")
            accessoryConnected = false
            listener.onPhysicalTransportState("DISCONNECTED")
            listener.onDisconnected("Samsung Accessory disconnected: $reason")
        }

        override fun onError(device: SamDevice?, reason: Int) {
            Log.w(TAG_SAM, "Accessory error device=$device reason=$reason")
            if (reason == SamAccessoryManager.ERROR_ACCESSORY_ALREADY_CONNECTED) {
                accessoryConnected = true
                listener.onPhysicalTransportState("CONNECTED")
                listener.onAccessoryConnected()
                connectBTMProviderService()
            } else {
                listener.onError("Samsung Accessory error: $reason")
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
        try {
            Log.i(TAG_SAM, "Connecting via Samsung Accessory BT to ${device.name}")
            listener.onPhysicalTransportState("CONNECTING")
            listener.onAccessoryConnecting()
            manager.connect(device.address, SA_TRANSPORT_TYPE)
        } catch (e: Exception) {
            Log.e(TAG_SAM, "Failed to start Samsung Accessory connection", e)
            listener.onError("Samsung Accessory connect failed", e)
        }
    }

    override fun disconnect(device: DeviceDescription?) {
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
        try {
            btmProviderService?.closeConnection()
        } catch (e: RuntimeException) {
            Log.w(TAG_SAP, "Failed to close Samsung Accessory socket", e)
        }
        btmProviderService = null

        try {
            samAccessoryManager?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG_SAM, "Failed to release Samsung Accessory manager", e)
        }
        samAccessoryManager = null
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

    private fun postUnavailable(reason: String, error: Throwable) {
        Handler(Looper.getMainLooper()).post {
            listener.onTransportUnavailable(reason, error)
        }
    }
}
