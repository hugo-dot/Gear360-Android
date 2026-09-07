package io.github.teccheck.gear360app.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.os.*
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.teccheck.gear360app.R
import io.github.teccheck.gear360app.service.BatteryState
import io.github.teccheck.gear360app.service.ConnectionState
import io.github.teccheck.gear360app.utils.DeviceDescription
import io.github.teccheck.gear360app.utils.DisplayStringUtils
import io.github.teccheck.gear360app.utils.ResUtils
import io.github.teccheck.gear360app.utils.SettingsHelper
import io.github.teccheck.gear360app.widget.ConnectionDots

private const val TAG = "HomeActivity"
const val EXTRA_DEVICE_DESCRIPTION = "device_description"
const val EXTRA_CONNECT_ON_START = "connect_on_start"

class HomeActivity : BaseActivity() {
    private lateinit var connectionDots: ConnectionDots
    private lateinit var connectionDevice: ImageView
    private lateinit var connectionGear: ImageView
    private lateinit var connectButton: Button
    private lateinit var selectButton: Button
    private lateinit var batteryIndicator: ImageView
    private lateinit var storageIndicator: TextView
    private lateinit var recyclerView: RecyclerView

    private var selectedDevice: DeviceDescription? = null
    private var connectOnStart = false
    private var autoConnectRequested = false
    private val connectionTimeoutHandler = Handler(Looper.getMainLooper())
    private val connectionTimeoutRunnable = Runnable {
        if (gear360Service?.connectionState?.value?.isConnecting() == true) {
            Toast.makeText(this, R.string.connection_timeout_samsung_accessory, Toast.LENGTH_LONG)
                .show()
            gear360Service?.disconnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        connectionDevice = findViewById(R.id.connect_phone_image)
        connectionDots = findViewById(R.id.dots)
        connectionGear = findViewById(R.id.connect_camera_image)
        batteryIndicator = findViewById(R.id.battery_indicator)
        storageIndicator = findViewById(R.id.storage_indicator)
        recyclerView = findViewById(R.id.recycler)
        connectButton = findViewById(R.id.btn_connect)
        selectButton = findViewById(R.id.btn_select_device)

        connectButton.setOnClickListener { connect() }
        selectButton.setOnClickListener { chooseDevice() }

        setDeviceConnectivityIndicator(false)

        selectedDevice = intent.getSerializableExtra(EXTRA_DEVICE_DESCRIPTION) as DeviceDescription?
        connectOnStart = intent.getBooleanExtra(EXTRA_CONNECT_ON_START, false)
        val settings = SettingsHelper(this)
        settings.updateKnownDeviceNames(this)
        if (selectedDevice == null)
            selectedDevice = settings.getLastConnectedDevice()

        if (selectedDevice == null) {
            chooseDevice()
            finish()
            return
        }

        updateSelectedDevice(selectedDevice)

        startRecyclerView()
        startGear360Service()
    }

    override fun onResume() {
        super.onResume()
        updateSelectedDevice(gear360Service?.selectedDevice?.value)
    }

    override fun onGearServiceConnected() {
        setDeviceConnectivityIndicator(true)
        gear360Service?.gear360Status?.observe(this) {
            batteryIndicator.setImageResource(
                ResUtils.getBatteryIcon(
                    it.battery ?: 0,
                    it.batteryState ?: BatteryState.NO_CHARGE
                )
            )

            storageIndicator.text = DisplayStringUtils.percentage(this, it.usedStoragePercentage())
            storageIndicator.setCompoundDrawablesRelativeWithIntrinsicBounds(
                ResUtils.getStorageIcon(
                    it.hasStorage()
                ), 0, 0, 0
            )
        }

        if (connectOnStart && !autoConnectRequested) {
            autoConnectRequested = true
            connect()
        }
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        setGearConnectivityIndicator(state)
        setDeviceConnectivityIndicator(state != ConnectionState.INVALID)

        if (!state.isConnecting()) {
            connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable)
        }

        val statusVisibility =
            if (state.hasControlChannel()) View.VISIBLE else View.INVISIBLE
        batteryIndicator.visibility = statusVisibility
        storageIndicator.visibility = statusVisibility
    }

    private fun startRecyclerView() {
        val dataSet = arrayOf(
            Property(
                ResUtils.getModelIcon(selectedDevice!!.type),
                R.string.btn_camera,
                getString(R.string.btn_camera_description)
            ) {
                val intent = Intent(this, SimpleCameraActivity::class.java)
                intent.putExtra(EXTRA_DEVICE_DESCRIPTION, selectedDevice)
                startActivity(intent)
            },
            Property(
                R.drawable.ic_baseline_settings_remote_24,
                R.string.btn_remote_control,
                getString(R.string.btn_remote_control_description),
                ActivityAction(RemoteControlActivity::class.java)
            ),
            Property(
                R.drawable.ic_baseline_settings_applications_24,
                R.string.btn_status,
                getString(R.string.btn_status_description),
                ActivityAction(StatusActivity::class.java)
            ),
            Property(
                R.drawable.ic_baseline_memory_24,
                R.string.btn_hardware,
                getString(R.string.btn_hardware_description),
                ActivityAction(HardwareInfoActivity::class.java)
            ),
            Property(
                R.drawable.baseline_chat_24,
                R.string.btn_messages,
                getString(R.string.btn_messages_description),
                ActivityAction(MessagesActivity::class.java)
            ),
            Property(
                R.drawable.ic_baseline_settings_applications_24,
                R.string.btn_bluetooth_diagnostics,
                getString(R.string.btn_bluetooth_diagnostics_description),
                ActivityAction(BluetoothDiagnosticsActivity::class.java)
            ),
            Property(
                R.drawable.ic_baseline_widgets_24,
                R.string.btn_test,
                getString(R.string.btn_test_description),
                ActivityAction(TestActivity::class.java)
            ),
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = PropertiesRecyclerAdapter(dataSet)
    }

    private fun connect() {
        val service = gear360Service
        if (service == null) {
            Toast.makeText(this, R.string.gear_service_starting, Toast.LENGTH_SHORT).show()
            return
        }

        if (service.connectionState.value?.hasControlChannel() == true) {
            service.disconnect()
            return
        }

        selectedDevice?.let {
            Log.d(TAG, "Connect to $it")
            Toast.makeText(this, R.string.camera_selected_connecting, Toast.LENGTH_SHORT).show()
            service.connect(it)
            connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable)
            connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable, 15_000)
        }
    }

    private fun chooseDevice() {
        startActivity(Intent(this, ConnectedDeviceActivity::class.java))
    }

    private fun updateSelectedDevice(device: DeviceDescription?) {
        if (device == null) return

        val settings = SettingsHelper(this)
        settings.setLastConnectedDevice(device)

        selectButton.text = device.name
        selectButton.setOnClickListener { chooseDevice() }
        connectionGear.setImageResource(ResUtils.getConnectModelIcon(device.type))
    }

    private fun setDeviceConnectivityIndicator(active: Boolean) {
        val colorRes = if (active) {
            R.color.connection_indicators_connected
        } else {
            R.color.connection_indicators_disconnected
        }
        val color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(colorRes)
        } else {
            resources.getColor(colorRes)
        }

        connectionDevice.imageTintList = ColorStateList.valueOf(color)
    }

    private fun setGearConnectivityIndicator(connectionState: ConnectionState) {
        connectionDots.setDotState(connectionState)

        val colorRes = if (connectionState.hasControlChannel()) {
            R.color.connection_indicators_connected
        } else {
            R.color.connection_indicators_disconnected
        }
        val color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(colorRes)
        } else {
            resources.getColor(colorRes)
        }

        connectionGear.imageTintList = ColorStateList.valueOf(color)

        connectButton.isEnabled = when (connectionState) {
            ConnectionState.INVALID -> false
            else -> !connectionState.isConnecting()
        }

        connectButton.setText(
            if (connectionState.hasControlChannel()) {
                R.string.btn_disconnect
            } else {
                R.string.btn_connect
            }
        )
    }

    private inner class ActivityAction<T>(private val activity: Class<T>) : Action {
        override fun execute() {
            startActivity(Intent(this@HomeActivity, activity))
        }
    }

    override fun onDestroy() {
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable)
        super.onDestroy()
    }
}
