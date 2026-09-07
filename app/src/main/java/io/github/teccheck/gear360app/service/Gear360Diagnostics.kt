package io.github.teccheck.gear360app.service

data class Gear360Diagnostics(
    val bluetoothDevice: String = "---",
    val bluetoothBond: String = "UNKNOWN",
    val physicalTransport: String = "DISCONNECTED",
    val backend: String = "UNKNOWN",
    val samsungFramework: String = "UNKNOWN",
    val samsungFrameworkVersion: String = "---",
    val accessoryTransport: String = "DISCONNECTED",
    val sapPeer: String = "NOT FOUND",
    val sapSocket: String = "DISCONNECTED",
    val logicalChannel204: String = "CLOSED",
    val channel204Rx: Boolean = false,
    val rx204Count: Int = 0,
    val lastRx: String = "---",
    val protocol: String = "DISCONNECTED",
    val wifi: String = "OFF",
    val camera: String = "NOT READY",
    val nativeBluetooth: String = "---",
    val lastError: String? = null
)
