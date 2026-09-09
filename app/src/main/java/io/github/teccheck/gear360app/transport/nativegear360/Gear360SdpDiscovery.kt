package io.github.teccheck.gear360app.transport.nativegear360

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "G360-SDP"

class Gear360SdpDiscovery(private val context: Context) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    fun discover(device: BluetoothDevice, timeoutMs: Long = 8_000L): List<UUID> {
        if (!AndroidPermissionUtils.hasBluetoothConnectPermission(appContext)) {
            Log.w(TAG, "BLUETOOTH_CONNECT missing; SDP unavailable")
            return emptyList()
        }

        val address = AndroidPermissionUtils.getBluetoothDeviceAddress(appContext, device)
        val latch = CountDownLatch(1)
        var discovered = cachedUuids(device)

        if (selectControlUuid(discovered) in setOf(
                GEAR360_SAP_UUID_PRIMARY,
                GEAR360_SAP_UUID_SECONDARY
            )
        ) {
            Log.i(
                TAG,
                "Using cached Gear360 SDP service immediately address=$address uuids=${discovered.toDisplayString()}"
            )
            return discovered
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_UUID) return
                val receivedDevice = bluetoothDeviceFromIntent(intent) ?: return
                val receivedAddress = AndroidPermissionUtils.getBluetoothDeviceAddress(
                    appContext,
                    receivedDevice
                )
                if (receivedAddress != address) return

                discovered = parcelUuidsFromIntent(intent).map { it.uuid }
                Log.i(TAG, "ACTION_UUID address=$address uuids=${discovered.toDisplayString()}")
                latch.countDown()
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            val started = try {
                device.fetchUuidsWithSdp()
            } catch (e: SecurityException) {
                Log.e(TAG, "fetchUuidsWithSdp missing permission", e)
                false
            } catch (e: RuntimeException) {
                Log.e(TAG, "fetchUuidsWithSdp failed", e)
                false
            }
            Log.i(TAG, "fetchUuidsWithSdp started=$started address=$address cached=${discovered.toDisplayString()}")

            if (started) {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: RuntimeException) {
                Log.w(TAG, "SDP receiver unregister failed", e)
            }
        }

        if (discovered.isEmpty()) {
            discovered = cachedUuids(device)
        }
        Log.i(TAG, "SDP final address=$address uuids=${discovered.toDisplayString()}")
        return discovered
    }

    @SuppressLint("MissingPermission")
    private fun cachedUuids(device: BluetoothDevice): List<UUID> {
        return try {
            device.uuids?.map { it.uuid }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: RuntimeException) {
            emptyList()
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

    @Suppress("DEPRECATION")
    private fun parcelUuidsFromIntent(intent: Intent): List<ParcelUuid> {
        return intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            ?.filterIsInstance<ParcelUuid>()
            .orEmpty()
    }

    companion object {
        // Observed in Android 16 dumpsys for the SM-R210 as RFCOMM SCN 1, MTU 990.
        val GEAR360_SAP_UUID_PRIMARY: UUID =
            UUID.fromString("a49eb41e-cb06-495c-9f4f-bb80a90cdf00")

        val GEAR360_SAP_UUID_SECONDARY: UUID =
            UUID.fromString("a49eb41e-cb06-495c-9f4f-aa80a90cdf4a")

        fun selectControlUuid(uuids: List<UUID>): UUID? {
            return when {
                uuids.contains(GEAR360_SAP_UUID_PRIMARY) -> GEAR360_SAP_UUID_PRIMARY
                uuids.contains(GEAR360_SAP_UUID_SECONDARY) -> GEAR360_SAP_UUID_SECONDARY
                uuids.isNotEmpty() -> uuids.first()
                else -> null
            }
        }
    }
}

internal fun List<UUID>.toDisplayString(): String {
    return if (isEmpty()) "none" else joinToString(",") { it.toString() }
}
