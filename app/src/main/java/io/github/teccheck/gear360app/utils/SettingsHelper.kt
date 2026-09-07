package io.github.teccheck.gear360app.utils

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.getSystemService
import io.github.teccheck.gear360app.service.DeviceType

private const val key_device_address = "last_connected_device"
private const val key_paired_devices = "paired_devices"
private const val key_device_type = "device_type_"
private const val key_device_name = "device_name_"

class SettingsHelper(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("prefs", 0)

    fun getLastConnectedDevice(): DeviceDescription? {
        return getDeviceDescription(prefs.getString(key_device_address, null) ?: return null)
    }

    fun setLastConnectedDevice(deviceDescription: DeviceDescription) {
        val editor = prefs.edit()
        editor.putString(key_device_address, deviceDescription.address)
        editor.apply()
    }

    fun addPairedDevice(deviceDescription: DeviceDescription) {
        val editor = prefs.edit()
        putIntoSet(editor, key_paired_devices, deviceDescription.address)
        editor.putString(getDeviceNameKey(deviceDescription.address), deviceDescription.name)
        editor.putString(getDeviceTypeKey(deviceDescription.address), deviceDescription.type.value)
        editor.apply()
    }

    fun getPairedDevices(): List<DeviceDescription> {
        val pairedDevices = prefs.getStringSet(key_paired_devices, null) ?: return listOf()
        return pairedDevices.mapNotNull(this::getDeviceDescription).toList()
    }

    fun syncBondedGear360Devices(context: Context): List<DeviceDescription> {
        val bondedDevices = AndroidPermissionUtils.getBondedGear360Devices(context)
        bondedDevices.forEach { addPairedDevice(it) }
        return getPairedDevices()
    }

    fun updateKnownDeviceNames(context: Context) {
        if (!AndroidPermissionUtils.hasBluetoothConnectPermission(context)) return

        val btAdapter = context.getSystemService<BluetoothManager>()?.adapter ?: return
        getPairedDevices()
            .mapNotNull {
                val bluetoothDevice = try {
                    btAdapter.getRemoteDevice(it.address)
                } catch (e: IllegalArgumentException) {
                    return@mapNotNull null
                }

                val name = AndroidPermissionUtils.getBluetoothDeviceName(context, bluetoothDevice) ?: it.name
                DeviceDescription(
                    it.address,
                    name,
                    AndroidPermissionUtils.inferGear360DeviceType(name)
                )
            }
            .forEach { addPairedDevice(it) }
    }

    private fun getDeviceDescription(address: String): DeviceDescription? {
        val name = getDeviceName(address) ?: return null
        val type = migrateDeviceTypeIfNeeded(address, name, getDeviceType(address))
        return DeviceDescription(address, name, type)
    }

    private fun migrateDeviceTypeIfNeeded(
        address: String,
        name: String,
        storedType: DeviceType?
    ): DeviceType {
        val inferredType = AndroidPermissionUtils.inferGear360DeviceType(name)
        if (storedType != inferredType) {
            prefs.edit()
                .putString(getDeviceTypeKey(address), inferredType.value)
                .apply()
        }
        return inferredType
    }

    private fun getDeviceName(address: String): String? {
        return prefs.getString(getDeviceNameKey(address), null)
    }

    private fun getDeviceType(address: String): DeviceType? {
        return prefs.getString(getDeviceTypeKey(address), null)?.let { DeviceType.fromString(it) }
    }

    private fun getDeviceNameKey(address: String): String {
        return key_device_name + address
    }

    private fun getDeviceTypeKey(address: String): String {
        return key_device_type + address
    }

    private fun putIntoSet(editor: SharedPreferences.Editor, key: String, element: String) {
        val updatedSet = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        updatedSet.add(element)
        editor.putStringSet(key, updatedSet)
    }
}
