package io.github.teccheck.gear360app.transport

import io.github.teccheck.gear360app.utils.DeviceDescription

interface Gear360ControlTransport {
    fun connect(device: DeviceDescription)
    fun disconnect(device: DeviceDescription?)
    fun send(channelId: Int, data: ByteArray): Boolean
    fun release()

    interface Listener {
        fun onBackendSelected(name: String) {}
        fun onSamsungFrameworkStatus(status: String, version: String? = null) {}
        fun onPhysicalTransportState(state: String) {}
        fun onNativeBluetoothDiagnostics(summary: String) {}
        fun onTransportUnavailable(reason: String, error: Throwable? = null)
        fun onAccessoryConnecting() {}
        fun onAccessoryConnected()
        fun onSapDiscoveryStarted() {}
        fun onSapPeerFound(name: String?, peer: String?, product: String?) {}
        fun onSapConnectionRequested(name: String?, peer: String?, product: String?) {}
        fun onSapSocketConnected(name: String?, peer: String?, product: String?) {}
        fun onSapPeerUnavailable() {}
        fun onSapPeerDiscoveryFailed(result: Int, message: String) {}
        fun onSapConnectionFailed(result: Int, message: String) {}
        fun onSapError(result: Int, message: String) {}
        fun onControlChannelConnected(name: String?, peer: String?, product: String?)
        fun onDisconnected(reason: String? = null)
        fun onError(reason: String, error: Throwable? = null)
        fun onReceive(channelId: Int, data: ByteArray)
    }
}
