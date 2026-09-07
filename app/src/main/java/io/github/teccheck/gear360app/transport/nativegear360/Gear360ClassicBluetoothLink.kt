package io.github.teccheck.gear360app.transport.nativegear360

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "G360-RFCOMM"
private const val READ_BUFFER_SIZE = 1024

class Gear360ClassicBluetoothLink(
    context: Context,
    private val listener: Listener
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, uuid: UUID) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "connect ignored; RFCOMM already running")
            return
        }

        executor.execute {
            val address = AndroidPermissionUtils.getBluetoothDeviceAddress(appContext, device)
                ?: "unknown"
            val name = AndroidPermissionUtils.getBluetoothDeviceName(appContext, device)
                ?: "unknown"
            try {
                listener.onState(ClassicLinkState.CLASSIC_CONNECTING, "uuid=$uuid device=$name $address")
                AndroidPermissionUtils.bluetoothAdapter(appContext)?.cancelDiscovery()

                Log.i(TAG, "CONNECTING device=$name address=$address uuid=$uuid")
                val openedSocket = device.createRfcommSocketToServiceRecord(uuid)
                socket = openedSocket
                openedSocket.connect()

                Log.i(TAG, "CONNECTED device=$name address=$address uuid=$uuid")
                listener.onState(ClassicLinkState.CLASSIC_CONNECTED, "uuid=$uuid device=$name $address")
                readLoop(openedSocket)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing permission while connecting RFCOMM", e)
                listener.onClosed("missing Bluetooth permission", e)
            } catch (e: IOException) {
                if (running.get()) {
                    Log.e(TAG, "RFCOMM connect/read failed", e)
                    listener.onClosed(e.message ?: "RFCOMM I/O failure", e)
                } else {
                    Log.i(TAG, "RFCOMM closed by local disconnect")
                }
            } catch (e: RuntimeException) {
                if (running.get()) {
                    Log.e(TAG, "RFCOMM runtime failure", e)
                    listener.onClosed(e.message ?: "RFCOMM runtime failure", e)
                } else {
                    Log.i(TAG, "RFCOMM runtime close during local disconnect")
                }
            } finally {
                closeSocket()
                running.set(false)
            }
        }
    }

    fun disconnect() {
        Log.i(TAG, "DISCONNECT requested")
        running.set(false)
        closeSocket()
    }

    @Synchronized
    fun write(data: ByteArray): Boolean {
        val openedSocket = socket
        if (!running.get() || openedSocket == null) {
            Log.w(TAG, "TX refused; RFCOMM socket is not connected")
            return false
        }

        return try {
            openedSocket.outputStream.write(data)
            openedSocket.outputStream.flush()
            Log.i(TAG, "TX len=${data.size} HEX=${data.toHexString()}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM write failed", e)
            listener.onClosed(e.message ?: "RFCOMM write failure", e)
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "RFCOMM write runtime failure", e)
            listener.onClosed(e.message ?: "RFCOMM write runtime failure", e)
            false
        }
    }

    fun release() {
        disconnect()
        executor.shutdownNow()
    }

    private fun readLoop(openedSocket: BluetoothSocket) {
        listener.onState(
            ClassicLinkState.SAP_NEGOTIATING,
            "RFCOMM open; waiting for Samsung Accessory framing"
        )

        val input = openedSocket.inputStream
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) {
                Log.w(TAG, "EOF")
                listener.onClosed("RFCOMM EOF before SAP channel 204", null)
                return
            }

            val bytes = buffer.copyOf(read)
            Log.i(TAG, "RX len=$read HEX=${bytes.toHexString()}")
            listener.onRx(bytes)
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "socket close failed", e)
        } finally {
            socket = null
        }
    }

    interface Listener {
        fun onState(state: ClassicLinkState, detail: String)
        fun onRx(data: ByteArray)
        fun onClosed(reason: String, error: Throwable?)
    }
}

enum class ClassicLinkState {
    CLASSIC_LISTENING,
    CLASSIC_CONNECTING,
    CLASSIC_CONNECTED,
    SAP_NEGOTIATING
}

internal fun ByteArray.toHexString(maxBytes: Int = 128): String {
    val clipped = if (size > maxBytes) copyOf(maxBytes) else this
    val suffix = if (size > maxBytes) " ..." else ""
    return clipped.joinToString(" ") { "%02X".format(it) } + suffix
}
