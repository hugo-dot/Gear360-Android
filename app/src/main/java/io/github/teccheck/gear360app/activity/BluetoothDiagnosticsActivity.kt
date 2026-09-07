package io.github.teccheck.gear360app.activity

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.teccheck.gear360app.R
import io.github.teccheck.gear360app.service.Gear360Diagnostics

class BluetoothDiagnosticsActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PropertiesRecyclerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hardware_info)
        setupBackButton()

        recyclerView = findViewById(R.id.recycler_view)
        adapter = PropertiesRecyclerAdapter(emptyArray())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        startGear360Service()
    }

    override fun onGearServiceConnected() {
        render(gear360Service?.diagnostics?.value ?: Gear360Diagnostics())
        gear360Service?.diagnostics?.observe(this, this::render)
    }

    private fun render(diagnostics: Gear360Diagnostics) {
        adapter.updateDataSet(
            arrayOf(
                Property(
                    R.drawable.ic_baseline_bluetooth_24,
                    R.string.diag_bluetooth_device,
                    diagnostics.bluetoothDevice
                ),
                Property(
                    R.drawable.ic_baseline_bluetooth_24,
                    R.string.diag_bluetooth_bond,
                    diagnostics.bluetoothBond
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_control_backend,
                    diagnostics.backend
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_samsung_framework,
                    diagnostics.samsungFramework
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_samsung_framework_version,
                    diagnostics.samsungFrameworkVersion
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_physical_transport,
                    diagnostics.physicalTransport
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_accessory_transport,
                    diagnostics.accessoryTransport
                ),
                Property(
                    R.drawable.ic_baseline_widgets_24,
                    R.string.diag_sap_peer,
                    diagnostics.sapPeer
                ),
                Property(
                    R.drawable.ic_baseline_settings_remote_24,
                    R.string.diag_sap_socket,
                    diagnostics.sapSocket
                ),
                Property(
                    R.drawable.baseline_chat_24,
                    R.string.diag_channel_204_rx,
                    if (diagnostics.channel204Rx) "YES" else "NO"
                ),
                Property(
                    R.drawable.baseline_chat_24,
                    R.string.diag_logical_channel_204,
                    diagnostics.logicalChannel204
                ),
                Property(
                    R.drawable.baseline_chat_24,
                    R.string.diag_rx_204_count,
                    diagnostics.rx204Count.toString()
                ),
                Property(
                    R.drawable.baseline_chat_24,
                    R.string.diag_last_rx,
                    diagnostics.lastRx
                ),
                Property(
                    R.drawable.ic_baseline_memory_24,
                    R.string.diag_protocol,
                    diagnostics.protocol
                ),
                Property(
                    R.drawable.ic_baseline_network_wifi_24,
                    R.string.diag_wifi,
                    diagnostics.wifi
                ),
                Property(
                    R.drawable.ic_baseline_photo_camera_24,
                    R.string.diag_camera,
                    diagnostics.camera
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_expected_mode,
                    getString(R.string.diag_expected_mode_value)
                ),
                Property(
                    R.drawable.ic_baseline_bluetooth_24,
                    R.string.diag_native_bluetooth,
                    diagnostics.nativeBluetooth
                ),
                Property(
                    R.drawable.ic_baseline_settings_applications_24,
                    R.string.diag_last_error,
                    diagnostics.lastError ?: "---"
                )
            )
        )
    }
}
