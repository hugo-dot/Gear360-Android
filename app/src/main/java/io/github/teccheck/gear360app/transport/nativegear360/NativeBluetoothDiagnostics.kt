package io.github.teccheck.gear360app.transport.nativegear360

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils

object NativeBluetoothDiagnostics {
    @SuppressLint("MissingPermission")
    fun describe(context: Context, device: BluetoothDevice): String {
        if (!AndroidPermissionUtils.hasBluetoothConnectPermission(context)) {
            return "BLUETOOTH_CONNECT permission missing"
        }

        val name = safe { device.name }.orEmpty().ifBlank { "unknown" }
        val address = safe { device.address }.orEmpty().ifBlank { "unknown" }
        val bond = safe { bondStateName(device.bondState) } ?: "UNKNOWN"
        val bluetoothClass = safe { classSummary(device.bluetoothClass) } ?: "unknown"
        val cachedUuids = safe {
            device.uuids
                ?.joinToString(",") { it.uuid.toString() }
                ?.ifBlank { "none" }
        } ?: "unavailable"

        return "Device=$name Address=$address Bond=$bond Class=$bluetoothClass CachedUUIDs=$cachedUuids"
    }

    fun bondStateName(state: Int): String {
        return when (state) {
            BluetoothDevice.BOND_NONE -> "NONE"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_BONDED -> "BONDED"
            else -> "UNKNOWN($state)"
        }
    }

    private fun classSummary(bluetoothClass: BluetoothClass?): String {
        if (bluetoothClass == null) return "unknown"
        return "major=${bluetoothClass.majorDeviceClass} device=${bluetoothClass.deviceClass}"
    }

    private inline fun <T> safe(block: () -> T): T? {
        return try {
            block()
        } catch (e: SecurityException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
