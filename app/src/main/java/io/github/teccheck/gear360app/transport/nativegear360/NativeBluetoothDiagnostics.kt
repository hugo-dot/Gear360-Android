package io.github.teccheck.gear360app.transport.nativegear360

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.provider.Settings
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import java.util.Locale

object NativeBluetoothDiagnostics {
    @SuppressLint("MissingPermission", "HardwareIds")
    fun localAdapterAddress(context: Context): String? {
        if (!AndroidPermissionUtils.hasBluetoothConnectPermission(context)) return null

        val secureAddress = safe {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }
        val adapterAddress = safe {
            AndroidPermissionUtils.bluetoothAdapter(context)?.address
        }
        val configuredAddress = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LOCAL_ADDRESS_OVERRIDE, null)
        return sequenceOf(secureAddress, adapterAddress, configuredAddress)
            .filterNotNull()
            .map { it.trim().uppercase(Locale.US) }
            .firstOrNull { BLUETOOTH_ADDRESS.matches(it) && it != REDACTED_ADDRESS }
    }

    fun setLocalAdapterAddressOverride(context: Context, address: String): Boolean {
        val normalized = address.trim().uppercase(Locale.US)
        if (!BLUETOOTH_ADDRESS.matches(normalized) || normalized == REDACTED_ADDRESS) return false
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LOCAL_ADDRESS_OVERRIDE, normalized)
            .apply()
        return true
    }

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

    private val BLUETOOTH_ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private const val REDACTED_ADDRESS = "02:00:00:00:00:00"
    private const val PREFERENCES = "native_bluetooth_identity"
    private const val LOCAL_ADDRESS_OVERRIDE = "local_address_override"
}
