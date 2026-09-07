package io.github.teccheck.gear360app.bluetooth

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageLog {
    val messages = mutableListOf<LogMessage>()

    fun messageReceived(channelId: Int, data: ByteArray) {
        val message = formatPacket("BT RX", channelId, data)
        Log.i(TAG, message)
        messages.add(LogMessage(message, Date(), SendDirection.FROM_CAMERA))
    }

    fun messageReceived(message: String) {
        Log.i(TAG, "RECV: $message")
        messages.add(LogMessage(message, Date(), SendDirection.FROM_CAMERA))
    }

    fun messageSent(channelId: Int, data: ByteArray) {
        val message = formatPacket("BT TX", channelId, data)
        Log.i(TAG, message)
        messages.add(LogMessage(message, Date(), SendDirection.TO_CAMERA))
    }

    fun messageSent(message: String) {
        Log.i(TAG, "SEND: $message")
        messages.add(LogMessage(message, Date(), SendDirection.TO_CAMERA))
    }

    private fun formatPacket(prefix: String, channelId: Int, data: ByteArray): String {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val hex = data.joinToString(" ") { "%02X".format(it) }
        val utf8 = data.toString(Charsets.UTF_8)

        return "$prefix $timestamp\ntransport=Samsung Accessory BT\nchannel=$channelId\nHEX=$hex\nUTF8=$utf8"
    }

    enum class SendDirection {
        TO_CAMERA,
        FROM_CAMERA
    }

    class LogMessage(val message: String, val date: Date, val direction: SendDirection)

    companion object {
        private const val TAG = "MessageLog"
    }
}
