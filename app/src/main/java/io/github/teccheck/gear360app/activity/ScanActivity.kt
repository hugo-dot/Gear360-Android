package io.github.teccheck.gear360app.activity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import io.github.teccheck.gear360app.R
import io.github.teccheck.gear360app.service.ConnectionState
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import io.github.teccheck.gear360app.utils.DeviceDescription
import io.github.teccheck.gear360app.utils.SettingsHelper

private const val TAG = "ScanActivity"
private const val TAG_BT = "G360-BT"
private const val CLASSIC_RESCAN_DELAY_MS = 1_500L

class ScanActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var leScanning = false
    private var classicReceiverRegistered = false
    private var classicDiscoveryWanted = false
    private var openingSimpleCamera = false
    // This is an ugly hack
    private var lastDeviceName: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private val classicDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.i(TAG_BT, "Classic Bluetooth discovery started")
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(TAG_BT, "Classic Bluetooth discovery finished")
                    if (classicDiscoveryWanted && !openingSimpleCamera) {
                        handler.postDelayed(
                            { startClassicDiscovery() },
                            CLASSIC_RESCAN_DELAY_MS
                        )
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = bluetoothDeviceFromIntent(intent) ?: return
                    addBluetoothDevice(device, source = "classic")
                }
            }
        }
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            addBluetoothDevice(result.device, source = "ble")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = BtDeviceAdapter()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            this.bluetoothAdapter = bluetoothManager.adapter
        } else {
            this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }
        this.bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        addBondedGear360Devices()

        startGear360Service()
    }

    override fun onResume() {
        super.onResume()
        addBondedGear360Devices()
        startDeviceDiscovery()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        Log.d(TAG, "Connection state: $state")
        if (state == ConnectionState.CONNECTED && !openingSimpleCamera) {
            val deviceDescription = DeviceDescription(
                gear360Service?.gear360Info?.btMac ?: return,
                lastDeviceName ?: return,
                gear360Service?.gear360Info?.modelName ?: return
            )

            val settings = SettingsHelper(this)
            settings.addPairedDevice(deviceDescription)
            settings.setLastConnectedDevice(deviceDescription)

            val intent = Intent(this, SimpleCameraActivity::class.java)
            intent.putExtra(EXTRA_DEVICE_DESCRIPTION, deviceDescription)
            startActivity(intent)
        }
    }

    private fun startDeviceDiscovery() {
        if (!AndroidPermissionUtils.hasBluetoothScanPermission(this)) {
            Log.w(TAG_BT, "Cannot scan; BLUETOOTH_SCAN permission missing")
            return
        }

        classicDiscoveryWanted = true
        startLeDiscovery()
        startClassicDiscovery()
    }

    private fun startLeDiscovery() {
        if (leScanning) return

        val scanner = bluetoothLeScanner ?: run {
            Log.w(TAG_BT, "Cannot start BLE scan; BluetoothLeScanner unavailable")
            return
        }

        leScanning = true
        try {
            Log.i(TAG_BT, "BLE scan started")
            scanner.startScan(leScanCallback)
        } catch (e: SecurityException) {
            leScanning = false
            Log.e(TAG, e.message, e)
        } catch (e: RuntimeException) {
            leScanning = false
            Log.e(TAG_BT, "BLE scan failed", e)
        }
    }

    private fun startClassicDiscovery() {
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG_BT, "Cannot start Classic discovery; BluetoothAdapter unavailable")
            return
        }

        registerClassicDiscoveryReceiver()

        try {
            if (adapter.isDiscovering) {
                Log.i(TAG_BT, "Classic discovery already running")
                return
            }

            val started = adapter.startDiscovery()
            Log.i(TAG_BT, "Classic discovery start requested result=$started")
        } catch (e: SecurityException) {
            Log.e(TAG_BT, "Classic discovery permission failure", e)
        } catch (e: RuntimeException) {
            Log.e(TAG_BT, "Classic discovery failed", e)
        }
    }

    private fun stopDeviceDiscovery() {
        classicDiscoveryWanted = false
        handler.removeCallbacksAndMessages(null)
        stopLeDiscovery()
        stopClassicDiscovery()
    }

    private fun stopLeDiscovery() {
        if (!leScanning) return

        leScanning = false
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, e.message, e)
        } catch (e: RuntimeException) {
            Log.e(TAG_BT, "Stopping BLE scan failed", e)
        }
    }

    private fun stopClassicDiscovery() {
        val adapter = bluetoothAdapter
        try {
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
                Log.i(TAG_BT, "Classic discovery cancelled")
            }
        } catch (e: SecurityException) {
            Log.e(TAG_BT, "Classic discovery cancel permission failure", e)
        } catch (e: RuntimeException) {
            Log.e(TAG_BT, "Classic discovery cancel failed", e)
        }

        unregisterClassicDiscoveryReceiver()
    }

    private fun registerClassicDiscoveryReceiver() {
        if (classicReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        }

        ContextCompat.registerReceiver(
            this,
            classicDiscoveryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        classicReceiverRegistered = true
    }

    private fun unregisterClassicDiscoveryReceiver() {
        if (!classicReceiverRegistered) return

        try {
            unregisterReceiver(classicDiscoveryReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG_BT, "Classic discovery receiver already unregistered", e)
        }
        classicReceiverRegistered = false
    }

    private fun onItemClick(scannedDevice: ScannedDevice) {
        if (openingSimpleCamera) {
            return
        }

        lastDeviceName = scannedDevice.name
        val deviceDescription = DeviceDescription(
            scannedDevice.address,
            scannedDevice.name,
            AndroidPermissionUtils.inferGear360DeviceType(scannedDevice.name)
        )

        val settings = SettingsHelper(this)
        settings.addPairedDevice(deviceDescription)
        settings.setLastConnectedDevice(deviceDescription)

        Toast.makeText(this, R.string.camera_selected_connecting, Toast.LENGTH_SHORT).show()
        openingSimpleCamera = true
        stopDeviceDiscovery()

        val intent = Intent(this, SimpleCameraActivity::class.java)
        intent.putExtra(EXTRA_DEVICE_DESCRIPTION, deviceDescription)
        intent.putExtra(EXTRA_CONNECT_ON_START, true)
        startActivity(intent)
    }

    private fun addBondedGear360Devices() {
        val adapter = bluetoothAdapter ?: return
        val deviceAdapter = recyclerView.adapter as? BtDeviceAdapter ?: return

        for (deviceDescription in AndroidPermissionUtils.getBondedGear360Devices(this)) {
            val device = try {
                adapter.getRemoteDevice(deviceDescription.address)
            } catch (e: IllegalArgumentException) {
                continue
            }

            deviceAdapter.addDevice(
                ScannedDevice(device, deviceDescription.address, deviceDescription.name)
            )
            Log.i(TAG_BT, "Bonded Gear 360 listed: ${deviceDescription.name}")
        }
    }

    private fun addBluetoothDevice(device: BluetoothDevice, source: String) {
        val name = AndroidPermissionUtils.getBluetoothDeviceName(this, device) ?: return
        val address = AndroidPermissionUtils.getBluetoothDeviceAddress(this, device) ?: return

        if (name.isBlank()) return
        if (!AndroidPermissionUtils.isGear360DeviceName(name)) {
            Log.d(TAG_BT, "Ignoring $source device $name")
            return
        }

        Log.i(TAG_BT, "Gear 360 discovered via $source: $name")
        (recyclerView.adapter as? BtDeviceAdapter)?.addDevice(
            ScannedDevice(device, address, name)
        )
    }

    private fun bluetoothDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    data class ScannedDevice(
        val device: BluetoothDevice,
        val address: String,
        val name: String
    )

    inner class BtDeviceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val devices = mutableListOf<ScannedDevice>()

        fun addDevice(device: ScannedDevice) {
            if (devices.any { it.address == device.address })
                return

            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }

        fun clear() {
            val count = devices.size
            devices.clear()
            if (count > 0) {
                notifyItemRangeRemoved(0, count)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = layoutInflater.inflate(R.layout.list_entry_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            val device = devices[position]

            val chip = view.findViewById<Chip>(R.id.chip)
            chip.setOnClickListener { this@ScanActivity.onItemClick(device) }
            chip.text = device.name
        }

        override fun getItemCount(): Int {
            return devices.size
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    override fun onDestroy() {
        stopDeviceDiscovery()
        super.onDestroy()
    }
}
