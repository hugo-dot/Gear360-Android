package io.github.teccheck.gear360app.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import io.github.teccheck.gear360app.R
import io.github.teccheck.gear360app.service.CaptureState
import io.github.teccheck.gear360app.service.ConnectionState
import io.github.teccheck.gear360app.utils.DeviceDescription
import io.github.teccheck.gear360app.utils.Gear360MediaTransfer
import io.github.teccheck.gear360app.utils.MediaImportResult
import io.github.teccheck.gear360app.utils.SettingsHelper
import io.github.teccheck.gear360app.utils.WifiUtils

private const val PHOTO_IMPORT_DELAY_MS = 5_000L
private const val VIDEO_IMPORT_DELAY_MS = 8_000L
private const val WIFI_AND_MEDIA_DISABLED_FOR_SAP_DIAGNOSTIC = true

class SimpleCameraActivity : BaseActivity() {
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var bluetoothText: TextView
    private lateinit var wifiText: TextView
    private lateinit var batteryText: TextView
    private lateinit var modeText: TextView
    private lateinit var recordingText: TextView
    private lateinit var folderText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var pairButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var wifiButton: MaterialButton
    private lateinit var photoButton: MaterialButton
    private lateinit var startVideoButton: MaterialButton
    private lateinit var stopVideoButton: MaterialButton
    private lateinit var galleryButton: MaterialButton
    private lateinit var chooseFolderButton: MaterialButton
    private lateinit var importNowButton: MaterialButton
    private lateinit var autoTransferSwitch: SwitchCompat

    private var selectedDevice: DeviceDescription? = null
    private var connectOnStart = false
    private var autoConnectRequested = false
    private var pendingImportAfterWifi = false

    private val handler = Handler(Looper.getMainLooper())

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

            val data = result.data ?: return@registerForActivityResult
            val uri = data.data ?: return@registerForActivityResult
            val takeFlags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: SecurityException) {
                // Some document providers grant access without a persistable flag.
            }

            Gear360MediaTransfer.saveDestinationFolder(this, uri)
            updateFolderLabel()
            Toast.makeText(this, R.string.folder_selected, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_camera)
        setupBackButton()

        titleText = findViewById(R.id.simple_camera_title)
        statusText = findViewById(R.id.simple_camera_status)
        bluetoothText = findViewById(R.id.simple_camera_bluetooth)
        wifiText = findViewById(R.id.simple_camera_wifi)
        batteryText = findViewById(R.id.simple_camera_battery)
        modeText = findViewById(R.id.simple_camera_mode)
        recordingText = findViewById(R.id.simple_camera_recording)
        folderText = findViewById(R.id.simple_camera_folder)
        progressBar = findViewById(R.id.simple_camera_progress)
        pairButton = findViewById(R.id.btn_pair_classic)
        connectButton = findViewById(R.id.btn_connect_camera)
        wifiButton = findViewById(R.id.btn_connect_camera_wifi)
        photoButton = findViewById(R.id.btn_take_simple_photo)
        startVideoButton = findViewById(R.id.btn_start_simple_video)
        stopVideoButton = findViewById(R.id.btn_stop_simple_video)
        galleryButton = findViewById(R.id.btn_gallery)
        chooseFolderButton = findViewById(R.id.btn_choose_transfer_folder)
        importNowButton = findViewById(R.id.btn_import_media_now)
        autoTransferSwitch = findViewById(R.id.switch_auto_transfer)

        @Suppress("DEPRECATION")
        selectedDevice = intent.getSerializableExtra(EXTRA_DEVICE_DESCRIPTION) as? DeviceDescription
        connectOnStart = intent.getBooleanExtra(EXTRA_CONNECT_ON_START, false)
        if (selectedDevice == null) {
            val settings = SettingsHelper(this)
            selectedDevice = settings.getLastConnectedDevice()
                ?: settings.syncBondedGear360Devices(this).firstOrNull()
        }

        selectedDevice?.let {
            SettingsHelper(this).setLastConnectedDevice(it)
            titleText.text = it.name
        } ?: run {
            setStatus(R.string.simple_camera_status_missing_device)
            setDeviceControlsEnabled(false)
        }

        pairButton.setOnClickListener { pairCamera() }
        connectButton.setOnClickListener { connectCamera() }
        wifiButton.setOnClickListener { connectCameraWifi() }
        photoButton.setOnClickListener { takePhoto() }
        startVideoButton.setOnClickListener { startVideo() }
        stopVideoButton.setOnClickListener { stopVideo() }
        galleryButton.setOnClickListener { importMediaNow() }
        chooseFolderButton.setOnClickListener { chooseTransferFolder() }
        importNowButton.setOnClickListener { importMediaNow() }

        autoTransferSwitch.isChecked = Gear360MediaTransfer.isAutoTransferEnabled(this)
        autoTransferSwitch.setOnCheckedChangeListener { _, checked ->
            Gear360MediaTransfer.setAutoTransferEnabled(this, checked)
        }
        if (WIFI_AND_MEDIA_DISABLED_FOR_SAP_DIAGNOSTIC) {
            pairButton.visibility = View.GONE
            wifiButton.isEnabled = false
            wifiButton.visibility = View.GONE
            galleryButton.isEnabled = false
            galleryButton.visibility = View.GONE
            chooseFolderButton.visibility = View.GONE
            folderText.visibility = View.GONE
            importNowButton.isEnabled = false
            importNowButton.visibility = View.GONE
            autoTransferSwitch.isEnabled = false
            autoTransferSwitch.visibility = View.GONE
        }

        updateFolderLabel()
        setCaptureControlsEnabled(false)
        updateCameraDetails()
        startGear360Service()
    }

    override fun onGearServiceConnected() {
        gear360Service?.gear360Status?.observe(this) { updateCameraDetails() }
        gear360Service?.gear360Config?.observe(this) { updateCameraDetails() }
        gear360Service?.gear360InfoLive?.observe(this) { updateCameraDetails() }

        onConnectionStateChanged(gear360Service?.connectionState?.value ?: ConnectionState.DISCONNECTED)
        if (connectOnStart && !autoConnectRequested) {
            autoConnectRequested = true
            selectedDevice?.let { gear360Service?.connectCamera(it) }
        }
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        progressBar.visibility = if (state.isConnecting() || state == ConnectionState.CAPTURING ||
            state == ConnectionState.DOWNLOADING
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }

        when {
            state == ConnectionState.WIFI_AVAILABLE && pendingImportAfterWifi -> {
                pendingImportAfterWifi = false
                setStatus(R.string.simple_camera_status_wifi_connected)
                importMediaNow()
            }

            state == ConnectionState.ERROR -> {
                setStatus(R.string.simple_camera_status_error)
                setCaptureControlsEnabled(false)
                connectButton.setText(R.string.connect_camera)
                connectButton.isEnabled = selectedDevice != null
            }

            state == ConnectionState.RECORDING -> {
                setStatus(R.string.simple_camera_status_recording)
                photoButton.isEnabled = false
                startVideoButton.isEnabled = false
                stopVideoButton.isEnabled = true
                connectButton.setText(R.string.btn_disconnect)
                connectButton.isEnabled = true
            }

            state == ConnectionState.CAPTURING -> {
                setStatus(R.string.simple_camera_status_busy)
                setCaptureControlsEnabled(false)
                connectButton.setText(R.string.btn_disconnect)
                connectButton.isEnabled = true
            }

            state.isReadyForCapture() -> {
                setStatus(
                    if (state == ConnectionState.WIFI_AVAILABLE) {
                        R.string.simple_camera_status_wifi_connected
                    } else {
                        R.string.simple_camera_status_connected
                    }
                )
                setCaptureControlsEnabled(true)
                connectButton.setText(R.string.btn_disconnect)
                connectButton.isEnabled = true
            }

            state == ConnectionState.WIFI_CONNECTING -> {
                setStatus(R.string.simple_camera_status_wifi_connecting)
                setCaptureControlsEnabled(false)
                connectButton.setText(R.string.btn_disconnect)
                connectButton.isEnabled = true
            }

            state.hasControlChannel() -> {
                setStatus(R.string.simple_camera_status_bt_connected)
                setCaptureControlsEnabled(false)
                connectButton.setText(R.string.btn_disconnect)
                connectButton.isEnabled = true
            }

            state.isConnecting() -> {
                setStatus(R.string.simple_camera_status_connecting)
                setCaptureControlsEnabled(false)
                connectButton.setText(R.string.connect_camera)
                connectButton.isEnabled = false
            }

            else -> {
                setStatus(R.string.simple_camera_status_disconnected)
                setCaptureControlsEnabled(false)
                connectButton.setText(R.string.connect_camera)
                connectButton.isEnabled = selectedDevice != null
            }
        }

        updateWifiButtonState(state)
        updateCameraDetails()
    }

    private fun pairCamera() {
        val service = gear360Service ?: return showServiceStarting()
        val device = selectedDevice ?: return setStatus(R.string.simple_camera_status_missing_device)
        service.pairCamera(device)
    }

    private fun connectCamera() {
        val service = gear360Service ?: return showServiceStarting()
        val device = selectedDevice ?: return setStatus(R.string.simple_camera_status_missing_device)
        val state = service.connectionState.value ?: ConnectionState.DISCONNECTED

        if (state.hasControlChannel() || state == ConnectionState.RECORDING ||
            state == ConnectionState.CAPTURING
        ) {
            service.disconnectCamera()
        } else {
            service.connectCamera(device)
        }
    }

    private fun connectCameraWifi() {
        if (WIFI_AND_MEDIA_DISABLED_FOR_SAP_DIAGNOSTIC) {
            setStatus(R.string.wifi_disabled_for_diagnostics)
            Toast.makeText(this, R.string.wifi_disabled_for_diagnostics, Toast.LENGTH_SHORT).show()
            return
        }

        val accepted = gear360Service?.connectCameraWifi() == true
        if (!accepted) {
            setStatus(R.string.camera_wifi_not_ready)
            Toast.makeText(this, R.string.camera_wifi_not_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        val accepted = gear360Service?.takePhoto() == true
        if (!accepted) {
            showCameraNotReady()
            return
        }

        Toast.makeText(this, R.string.camera_command_sent, Toast.LENGTH_SHORT).show()
        scheduleAutoImport(PHOTO_IMPORT_DELAY_MS)
    }

    private fun startVideo() {
        val accepted = gear360Service?.startVideo() == true
        if (!accepted) {
            showCameraNotReady()
            return
        }

        Toast.makeText(this, R.string.camera_command_sent, Toast.LENGTH_SHORT).show()
    }

    private fun stopVideo() {
        val accepted = gear360Service?.stopVideo() == true
        if (!accepted) {
            showCameraNotReady()
            return
        }

        Toast.makeText(this, R.string.camera_command_sent, Toast.LENGTH_SHORT).show()
        scheduleAutoImport(VIDEO_IMPORT_DELAY_MS)
    }

    private fun chooseTransferFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        folderLauncher.launch(intent)
    }

    private fun importMediaNow() {
        if (WIFI_AND_MEDIA_DISABLED_FOR_SAP_DIAGNOSTIC) {
            setStatus(R.string.wifi_disabled_for_diagnostics)
            Toast.makeText(this, R.string.wifi_disabled_for_diagnostics, Toast.LENGTH_SHORT).show()
            return
        }

        if (Gear360MediaTransfer.getDestinationFolderUri(this) == null) {
            setStatus(R.string.media_import_no_folder)
            Toast.makeText(this, R.string.media_import_no_folder, Toast.LENGTH_SHORT).show()
            return
        }

        if (WifiUtils.activeNetwork() == null) {
            val hasWifiInfo = gear360Service?.gear360Info?.apSSID?.isNotBlank() == true
            if (hasWifiInfo && gear360Service?.connectCameraWifi() == true) {
                pendingImportAfterWifi = true
                setStatus(R.string.simple_camera_status_wifi_connecting)
                return
            }
        }

        setStatus(R.string.media_import_started)
        progressBar.visibility = View.VISIBLE
        importNowButton.isEnabled = false
        galleryButton.isEnabled = false

        Gear360MediaTransfer.importMedia(this) { result ->
            progressBar.visibility = View.GONE
            importNowButton.isEnabled = true
            galleryButton.isEnabled = true
            showImportResult(result)
        }
    }

    private fun scheduleAutoImport(delayMs: Long) {
        if (WIFI_AND_MEDIA_DISABLED_FOR_SAP_DIAGNOSTIC) return
        if (!Gear360MediaTransfer.isAutoTransferEnabled(this)) return

        handler.postDelayed({
            importMediaNow()
        }, delayMs)
    }

    private fun showImportResult(result: MediaImportResult) {
        val message = when {
            !result.isSuccess -> getString(R.string.media_import_failed, result.errorMessage)
            result.importedCount == 0 && result.skippedCount == 0 ->
                getString(R.string.media_import_no_files)
            else -> getString(
                R.string.media_import_done,
                result.importedCount,
                result.skippedCount
            )
        }

        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateCameraDetails() {
        val state = gear360Service?.connectionState?.value ?: ConnectionState.DISCONNECTED
        val info = gear360Service?.gear360Info
        val status = gear360Service?.gear360Status?.value
        val config = gear360Service?.gear360Config?.value
        val wifiConnected = WifiUtils.activeNetwork() != null || state == ConnectionState.WIFI_AVAILABLE

        bluetoothText.text = getString(
            R.string.simple_camera_bluetooth_state,
            if (state.hasControlChannel() || state.isReadyForCapture()) "●" else "○"
        )
        wifiText.text = getString(
            R.string.simple_camera_wifi_state,
            if (wifiConnected) "●" else "○"
        )
        batteryText.text = getString(
            R.string.simple_camera_battery_state,
            status?.battery?.let { "$it%" } ?: getString(R.string.no_value)
        )
        modeText.text = getString(
            R.string.simple_camera_mode_state,
            config?.mode?.value ?: getString(R.string.no_value)
        )

        val recording = status?.recordState == CaptureState.RECORDING ||
            status?.captureState == CaptureState.RECORDING ||
            state == ConnectionState.RECORDING
        recordingText.visibility = if (recording) View.VISIBLE else View.GONE
    }

    private fun updateFolderLabel() {
        folderText.text = Gear360MediaTransfer.getDestinationFolderName(this)
            ?: getString(R.string.no_transfer_folder)
    }

    private fun setStatus(stringRes: Int) {
        statusText.setText(stringRes)
    }

    private fun setDeviceControlsEnabled(enabled: Boolean) {
        pairButton.isEnabled = enabled
        connectButton.isEnabled = enabled
        wifiButton.isEnabled = false
        setCaptureControlsEnabled(false)
    }

    private fun setCaptureControlsEnabled(enabled: Boolean) {
        photoButton.isEnabled = enabled
        startVideoButton.isEnabled = enabled
        stopVideoButton.isEnabled = enabled
    }

    private fun showServiceStarting() {
        Toast.makeText(this, R.string.gear_service_starting, Toast.LENGTH_SHORT).show()
    }

    private fun showCameraNotReady() {
        setStatus(R.string.camera_not_connected)
        Toast.makeText(this, R.string.camera_not_connected, Toast.LENGTH_SHORT).show()
    }

    private fun updateWifiButtonState(state: ConnectionState) {
        if (WIFI_AND_MEDIA_DISABLED_FOR_SAP_DIAGNOSTIC) {
            wifiButton.isEnabled = false
            galleryButton.isEnabled = false
            importNowButton.isEnabled = false
            autoTransferSwitch.isEnabled = false
            return
        }

        val hasWifiInfo = gear360Service?.gear360Info?.apSSID?.isNotBlank() == true
        wifiButton.isEnabled = hasWifiInfo &&
            state.hasControlChannel() &&
            state != ConnectionState.WIFI_CONNECTING &&
            WifiUtils.activeNetwork() == null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
