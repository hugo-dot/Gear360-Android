package io.github.teccheck.gear360app.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import io.github.teccheck.gear360app.utils.SettingsHelper
import io.github.teccheck.gear360app.BuildConfig
import io.github.teccheck.gear360app.transport.nativegear360.NativeBluetoothDiagnostics
import android.util.Log

private const val PERMISSION_REQUEST_CODE = 0xACDC
private const val EXTRA_DEBUG_LOCAL_BT_ADDRESS = "gear360_debug_local_bt_address"
private const val TAG = "G360-SAP-AUTH"

class StartActivity : AppCompatActivity() {
    private var launchedNext = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEBUG_LOCAL_BT_ADDRESS)?.let { address ->
                if (NativeBluetoothDiagnostics.setLocalAdapterAddressOverride(this, address)) {
                    Log.i(TAG, "Debug local Bluetooth identity stored")
                } else {
                    Log.e(TAG, "Rejected malformed debug local Bluetooth identity")
                }
            }
        }

        if (checkPermissions()) {
            startNextActivityIfReady()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissions()) {
            startNextActivityIfReady()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (AndroidPermissionUtils.hasRequiredStartupPermissions(this)) {
                startNextActivityIfReady()
            }
        }
    }

    private fun startNextActivityIfReady() {
        if (launchedNext) return
        launchedNext = true
        startNextActivity()
    }

    private fun startNextActivity() {
        val settings = SettingsHelper(this)
        settings.syncBondedGear360Devices(this)
        val lastConnectedDevice = settings.getLastConnectedDevice()
            ?: settings.getPairedDevices().firstOrNull()

        if (lastConnectedDevice != null) {
            val intent = Intent(this, SimpleCameraActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra(EXTRA_DEVICE_DESCRIPTION, lastConnectedDevice)
            intent.putExtra(EXTRA_CONNECT_ON_START, true)
            startActivity(intent)
        } else {
            val intent = Intent(this, ScanActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    private fun checkPermissions(): Boolean {
        val requiredMissing = AndroidPermissionUtils.missingPermissions(
            this,
            AndroidPermissionUtils.requiredStartupPermissions()
        )
        val optionalMissing = AndroidPermissionUtils.missingPermissions(
            this,
            AndroidPermissionUtils.optionalStartupPermissions()
        )

        if (requiredMissing.isNotEmpty() || optionalMissing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                (requiredMissing + optionalMissing).distinct().toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        return requiredMissing.isEmpty()
    }
}
