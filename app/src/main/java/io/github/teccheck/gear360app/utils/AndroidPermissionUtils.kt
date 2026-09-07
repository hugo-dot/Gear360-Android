package io.github.teccheck.gear360app.utils

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.teccheck.gear360app.service.DeviceType

object AndroidPermissionUtils {
    fun requiredStartupPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun optionalStartupPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return permissions
    }

    fun missingPermissions(context: Context, permissions: List<String>): List<String> {
        return permissions.filterNot { hasPermission(context, it) }
    }

    fun hasRequiredStartupPermissions(context: Context): Boolean {
        return missingPermissions(context, requiredStartupPermissions()).isEmpty()
    }

    fun hasBluetoothScanPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun hasNearbyWifiDevicesPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothAdapter(context: Context): BluetoothAdapter? {
        return context.getSystemService<BluetoothManager>()?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothDeviceAddress(context: Context, device: BluetoothDevice): String? {
        if (!hasBluetoothConnectPermission(context)) return null

        return try {
            device.address
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothDeviceName(context: Context, device: BluetoothDevice): String? {
        if (!hasBluetoothConnectPermission(context)) return null

        return try {
            device.name ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias
            } else {
                null
            }
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getBondState(context: Context, device: BluetoothDevice): Int? {
        if (!hasBluetoothConnectPermission(context)) return null

        return try {
            device.bondState
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getBondedGear360Devices(context: Context): List<DeviceDescription> {
        if (!hasBluetoothConnectPermission(context)) return emptyList()

        val adapter = bluetoothAdapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices
                .mapNotNull { device ->
                    val name = getBluetoothDeviceName(context, device) ?: return@mapNotNull null
                    val address = getBluetoothDeviceAddress(context, device) ?: return@mapNotNull null
                    if (!isGear360DeviceName(name)) return@mapNotNull null

                    DeviceDescription(address, name, inferGear360DeviceType(name))
                }
                .distinctBy { it.address }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun isGear360DeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false

        return name.contains("Gear 360", ignoreCase = true) ||
            name.contains("Gear360", ignoreCase = true) ||
            name.contains(DeviceType.R210.value, ignoreCase = true) ||
            name.contains(DeviceType.C200.value, ignoreCase = true)
    }

    fun inferGear360DeviceType(name: String?): DeviceType {
        val safeName = name.orEmpty()
        return when {
            safeName.contains(DeviceType.C200.value, ignoreCase = true) -> DeviceType.C200
            safeName.contains(DeviceType.R210.value, ignoreCase = true) -> DeviceType.R210
            safeName.contains("2016", ignoreCase = true) -> DeviceType.C200
            safeName.contains("2017", ignoreCase = true) -> DeviceType.R210
            safeName.contains("Gear 360", ignoreCase = true) ||
                safeName.contains("Gear360", ignoreCase = true) -> DeviceType.R210
            else -> DeviceType.C200
        }
    }
}
