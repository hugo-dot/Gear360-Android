package io.github.teccheck.gear360app.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG_SAM = "LEGACY-SAM"

object SamsungAccessoryFramework {
    const val PACKAGE_NAME = "com.samsung.accessory"
    const val ACCESSORY_FRAMEWORK_PERMISSION = "com.samsung.accessory.permission.ACCESSORY_FRAMEWORK"

    fun inspect(context: Context): Status {
        val appContext = context.applicationContext
        val appAccessoryPermissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            ACCESSORY_FRAMEWORK_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        val frameworkBtConnectGranted = appContext.packageManager.checkPermission(
            Manifest.permission.BLUETOOTH_CONNECT,
            PACKAGE_NAME
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            }

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            val version = listOfNotNull(packageInfo.versionName, versionCode)
                .joinToString(" / ")
                .ifBlank { "---" }

            Status(
                installed = true,
                broken = false,
                appAccessoryPermissionGranted = appAccessoryPermissionGranted,
                frameworkBtConnectGranted = frameworkBtConnectGranted,
                version = version
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Status(
                installed = false,
                broken = false,
                appAccessoryPermissionGranted = appAccessoryPermissionGranted,
                frameworkBtConnectGranted = false,
                version = "---"
            )
        } catch (e: RuntimeException) {
            Log.e(TAG_SAM, "framework package inspection failed", e)
            Status(
                installed = false,
                broken = true,
                appAccessoryPermissionGranted = appAccessoryPermissionGranted,
                frameworkBtConnectGranted = frameworkBtConnectGranted,
                version = "---"
            )
        }
    }

    data class Status(
        val installed: Boolean,
        val broken: Boolean,
        val appAccessoryPermissionGranted: Boolean,
        val frameworkBtConnectGranted: Boolean,
        val version: String
    ) {
        val diagnosticStatus: String
            get() = when {
                broken -> "BROKEN"
                !installed -> "ABSENT"
                appAccessoryPermissionGranted -> "PRESENT"
                else -> "PRESENT APP_ACCESSORY_PERMISSION_DENIED"
            } + " FRAMEWORK_BT_CONNECT=" + if (frameworkBtConnectGranted) "GRANTED" else "DENIED"

        val presentText: String
            get() = when {
                broken -> "ERROR"
                installed -> "YES"
                else -> "NO"
            }

        val permissionText: String
            get() = buildString {
                append("APP_ACCESSORY=")
                append(if (appAccessoryPermissionGranted) "GRANTED" else "DENIED")
                if (installed || broken) {
                    append(" FRAMEWORK_BT_CONNECT=")
                    append(if (frameworkBtConnectGranted) "GRANTED" else "DENIED")
                }
            }
    }
}
