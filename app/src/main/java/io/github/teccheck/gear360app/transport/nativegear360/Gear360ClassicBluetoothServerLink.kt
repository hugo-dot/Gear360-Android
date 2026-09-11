package io.github.teccheck.gear360app.transport.nativegear360

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapHandshake
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVER_TAG = "G360-RFCOMM"

class Gear360ClassicBluetoothServerLink(
    context: Context,
    private val listener: Listener
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val accepted = AtomicBoolean(false)
    private val serverSockets = CopyOnWriteArrayList<BluetoothServerSocket>()
    @Volatile private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun listen(uuids: List<UUID>) {
        if (!running.compareAndSet(false, true)) {
            Log.w(SERVER_TAG, "server listen ignored; RFCOMM server already running")
            return
        }

        val adapter = AndroidPermissionUtils.bluetoothAdapter(appContext)
        if (adapter == null) {
            running.set(false)
            listener.onClosed("Bluetooth adapter unavailable", null)
            return
        }

        uuids.distinct().forEach { uuid ->
            executor.execute {
                try {
                    listener.onState(ClassicLinkState.CLASSIC_LISTENING, "uuid=$uuid")
                    Log.i(SERVER_TAG, "LISTENING service=${SapHandshake.PROFILE_NAME} uuid=$uuid")
                    val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                        SapHandshake.PROFILE_NAME,
                        uuid
                    )
                    serverSockets += serverSocket
                    val acceptedSocket = serverSocket.accept()
                    if (!running.get()) {
                        closeSocket(acceptedSocket)
                        return@execute
                    }

                    if (!accepted.compareAndSet(false, true)) {
                        Log.i(SERVER_TAG, "closing duplicate inbound RFCOMM socket uuid=$uuid")
                        closeSocket(acceptedSocket)
                        return@execute
                    }

                    socket = acceptedSocket
                    closeServerSockets()
                    val remote = acceptedSocket.remoteDevice
                    val detail = describeRemote(remote, uuid)
                    Log.i(SERVER_TAG, "ACCEPTED $detail")
                    listener.onState(ClassicLinkState.CLASSIC_CONNECTED, detail)
                    try {
                        readLoop(acceptedSocket)
                    } catch (error: IOException) {
                        if (running.get()) {
                            Log.e(SERVER_TAG, "Accepted RFCOMM connection lost", error)
                            listener.onClosed("Accepted RFCOMM connection lost: ${error.message}", error)
                        }
                    } finally {
                        closeSocket(acceptedSocket)
                        if (socket === acceptedSocket) socket = null
                    }
                } catch (e: SecurityException) {
                    if (running.get() && !accepted.get()) {
                        Log.e(SERVER_TAG, "Missing permission while listening RFCOMM", e)
                        listener.onClosed("missing Bluetooth permission", e)
                    }
                } catch (e: IOException) {
                    if (running.get() && !accepted.get()) {
                        Log.e(SERVER_TAG, "RFCOMM server failed", e)
                        listener.onClosed(e.message ?: "RFCOMM server failure", e)
                    } else {
                        Log.i(SERVER_TAG, "RFCOMM server socket closed")
                    }
                } catch (e: RuntimeException) {
                    if (running.get() && !accepted.get()) {
                        Log.e(SERVER_TAG, "RFCOMM server runtime failure", e)
                        listener.onClosed(e.message ?: "RFCOMM server runtime failure", e)
                    } else {
                        Log.i(SERVER_TAG, "RFCOMM server runtime close")
                    }
                }
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    fun disconnect() {
        Log.i(SERVER_TAG, "SERVER DISCONNECT requested")
        running.set(false)
        closeServerSockets()
        closeSocket(socket)
        socket = null
    }

    @Synchronized
    fun write(data: ByteArray): Boolean {
        val openedSocket = socket
        if (!running.get() || openedSocket == null) {
            Log.w(SERVER_TAG, "server TX refused; RFCOMM socket is not connected")
            return false
        }

        return try {
            openedSocket.outputStream.write(data)
            openedSocket.outputStream.flush()
            Log.i(SERVER_TAG, "SERVER TX len=${data.size} HEX=${data.toHexString()}")
            true
        } catch (e: IOException) {
            Log.e(SERVER_TAG, "RFCOMM server write failed", e)
            listener.onClosed(e.message ?: "RFCOMM server write failure", e)
            false
        } catch (e: RuntimeException) {
            Log.e(SERVER_TAG, "RFCOMM server write runtime failure", e)
            listener.onClosed(e.message ?: "RFCOMM server write runtime failure", e)
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
            "inbound RFCOMM open; waiting for Samsung Accessory framing"
        )

        val input = openedSocket.inputStream
        val buffer = ByteArray(1024)
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) {
                Log.w(SERVER_TAG, "SERVER EOF")
                listener.onClosed("RFCOMM server EOF before SAP channel 204", null)
                return
            }

            val bytes = buffer.copyOf(read)
            Log.i(SERVER_TAG, "SERVER RX len=$read HEX=${bytes.toHexString()}")
            listener.onRx(bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun describeRemote(remote: BluetoothDevice?, uuid: UUID): String {
        if (remote == null) return "uuid=$uuid remote=unknown"
        val address = AndroidPermissionUtils.getBluetoothDeviceAddress(appContext, remote)
            ?: "unknown"
        val name = AndroidPermissionUtils.getBluetoothDeviceName(appContext, remote)
            ?: "unknown"
        return "uuid=$uuid remote=$name $address"
    }

    private fun closeServerSockets() {
        serverSockets.forEach { serverSocket ->
            try {
                serverSocket.close()
            } catch (e: IOException) {
                Log.w(SERVER_TAG, "server socket close failed", e)
            }
        }
        serverSockets.clear()
    }

    private fun closeSocket(openedSocket: BluetoothSocket?) {
        try {
            openedSocket?.close()
        } catch (e: IOException) {
            Log.w(SERVER_TAG, "accepted socket close failed", e)
        }
    }

    interface Listener {
        fun onState(state: ClassicLinkState, detail: String)
        fun onRx(data: ByteArray)
        fun onClosed(reason: String, error: Throwable?)
    }
}
