package io.github.teccheck.gear360app.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import io.github.teccheck.gear360app.utils.SettingsHelper

private const val PERMISSION_REQUEST_CODE = 0xACDC

class StartActivity : AppCompatActivity() {
    private var launchedNext = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
