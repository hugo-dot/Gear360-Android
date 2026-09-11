package io.github.teccheck.gear360app.transport.nativegear360.sap

import java.io.ByteArrayOutputStream

/** Peer Description 2.1 used before CAPEX on the Gear 360 Bluetooth link. */
class SapPeerDescription(
    private val identity: SapPeerIdentity
) {
    private var negotiatedProtocolVersion = PROTOCOL_VERSION_2_1

    fun messageType(payload: ByteArray): Int? = payload.firstOrNull()?.toInt()?.and(0xff)

    fun protocolVersion(payload: ByteArray): Int? {
        if (payload.size < 3) return null
        return readUInt16(payload, 1)
    }

    fun parse(payload: ByteArray): SapPeerDescriptionMessage? {
        if (payload.size < MIN_FULL_MESSAGE_SIZE) return null

        var offset = 0
        val messageType = payload[offset++].toInt() and 0xff
        val protocolVersion = readUInt16(payload, offset).also { offset += 2 }
        val softwareVersion = readUInt16(payload, offset).also { offset += 2 }
        if (messageType == MESSAGE_RESPONSE) {
            if (payload.size < MIN_FULL_MESSAGE_SIZE + 1 || payload[offset++].toInt() != 0) return null
        }
        val configCode = payload[offset++].toInt() and 0xff
        val apduSize = readInt32(payload, offset).also { offset += 4 }
        val ssduSize = readUInt16(payload, offset).also { offset += 2 }
        val maxSessions = readUInt16(payload, offset).also { offset += 2 }
        val sessionTimeoutMs = readUInt16(payload, offset).also { offset += 2 }
        val transportMode = payload[offset++].toInt() and 0xff
        val transportWindowSize = readUInt16(payload, offset).also { offset += 2 }
        val connectionlessMode = payload[offset++].toInt() and 0xff

        val strings = ArrayList<String>(4)
        repeat(4) {
            val end = payload.indexOfFrom(STRING_TERMINATOR, offset)
            if (end < 0) return null
            strings += payload.copyOfRange(offset, end).toString(Charsets.UTF_8)
            offset = end + 1
        }

        return SapPeerDescriptionMessage(
            messageType = messageType,
            protocolVersion = protocolVersion,
            softwareVersion = softwareVersion,
            configCode = configCode,
            apduSize = apduSize,
            ssduSize = ssduSize,
            maxSessions = maxSessions,
            sessionTimeoutMs = sessionTimeoutMs,
            transportMode = transportMode,
            transportWindowSize = transportWindowSize,
            connectionlessMode = connectionlessMode,
            productId = strings[0],
            manufacturerId = strings[1],
            friendlyName = strings[2],
            providedServiceProfile = strings[3]
        )
    }

    fun composeOffer(peerProtocolVersion: Int): ByteArray {
        require(peerProtocolVersion >= PROTOCOL_VERSION_2_0) {
            "Unsupported peer-description protocol version: 0x${peerProtocolVersion.toString(16)}"
        }
        negotiatedProtocolVersion = minOf(LOCAL_PROTOCOL_VERSION, peerProtocolVersion)
        return composeFullMessage(MESSAGE_OFFER)
    }

    /** SAP 2.1 request/response are device packets, without a session header. */
    fun composeLegacyResponse(peer: SapPeerDescriptionMessage): ByteArray {
        require(peer.messageType == MESSAGE_REQUEST && peer.protocolVersion == PROTOCOL_VERSION_2_1)
        negotiatedProtocolVersion = peer.protocolVersion
        return composeFullMessage(MESSAGE_RESPONSE, peer)
    }

    /**
     * SAPdProbeMessageUtils.composeConfirmMessageInternal() uses the compact
     * six-byte confirmation, not the full Peer Description payload.
     */
    fun composeConfirm(status: Int = STATUS_ACCEPTED): ByteArray = byteArrayOf(
        MESSAGE_CONFIRM.toByte(),
        ((negotiatedProtocolVersion ushr 8) and 0xff).toByte(),
        (negotiatedProtocolVersion and 0xff).toByte(),
        ((SOFTWARE_VERSION_2_1 ushr 8) and 0xff).toByte(),
        (SOFTWARE_VERSION_2_1 and 0xff).toByte(),
        (status and 0xff).toByte()
    )

    fun confirmStatus(payload: ByteArray): Int? {
        if (messageType(payload) != MESSAGE_CONFIRM || payload.size < CONFIRM_MESSAGE_SIZE) {
            return null
        }
        return payload[5].toInt() and 0xff
    }

    private fun composeFullMessage(messageType: Int, peer: SapPeerDescriptionMessage? = null): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(messageType)
        output.writeUInt16(negotiatedProtocolVersion)
        output.writeUInt16(SOFTWARE_VERSION_2_1)
        if (messageType == MESSAGE_RESPONSE) output.write(STATUS_ACCEPTED)
        output.write(CONFIG_CODE_FULL)
        output.writeInt32(minOf(BLUETOOTH_APDU_SIZE, peer?.apduSize ?: BLUETOOTH_APDU_SIZE))
        output.writeUInt16(minOf(BLUETOOTH_SSDU_SIZE, peer?.ssduSize ?: BLUETOOTH_SSDU_SIZE))
        output.writeUInt16(minOf(MAX_SESSIONS, peer?.maxSessions ?: MAX_SESSIONS))
        output.writeUInt16(minOf(SESSION_TIMEOUT_MS, peer?.sessionTimeoutMs ?: SESSION_TIMEOUT_MS))
        output.write(if (peer == null || peer.transportMode == TRANSPORT_MODE_RELIABLE) TRANSPORT_MODE_RELIABLE else 0)
        output.writeUInt16(minOf(TRANSPORT_WINDOW_SIZE, peer?.transportWindowSize ?: TRANSPORT_WINDOW_SIZE))
        output.write(minOf(CONNECTIONLESS_MODE, peer?.connectionlessMode ?: CONNECTIONLESS_MODE))
        output.writeTerminated(identity.productId)
        output.writeTerminated(identity.manufacturerId)
        output.writeTerminated(identity.friendlyName)
        output.writeTerminated(PROVIDED_SERVICE_PROFILE)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeTerminated(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        write(encoded.copyOf(minOf(encoded.size, MAX_ID_BYTES)))
        write(STRING_TERMINATOR.toInt())
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun ByteArray.indexOfFrom(value: Byte, startIndex: Int): Int {
        for (index in startIndex until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    companion object {
        const val MESSAGE_PROBE = 1
        const val MESSAGE_OFFER = 2
        const val MESSAGE_COUNTER_OFFER = 3
        const val MESSAGE_CONFIRM = 4
        const val MESSAGE_REQUEST = 5
        const val MESSAGE_RESPONSE = 6
        const val MESSAGE_LEGACY_CONFIRM = 7
        const val MESSAGE_SUCCESS = 9
        const val MESSAGE_ERROR = 8
        const val STATUS_ACCEPTED = 0

        fun isLegacyDevicePacket(payload: ByteArray): Boolean {
            if (payload.contentEquals(byteArrayOf(MESSAGE_SUCCESS.toByte()))) return true
            if (payload.size < 6 || payload[1] != 2.toByte() || payload[2] != 1.toByte()) return false
            return when (payload[0].toInt() and 0xff) {
                MESSAGE_REQUEST, MESSAGE_RESPONSE -> payload.size >= MIN_FULL_MESSAGE_SIZE
                MESSAGE_LEGACY_CONFIRM, MESSAGE_ERROR -> payload.size == CONFIRM_MESSAGE_SIZE
                else -> false
            }
        }

        private const val PROTOCOL_VERSION_2_0 = 0x0200
        private const val PROTOCOL_VERSION_2_1 = 0x0201
        private const val LOCAL_PROTOCOL_VERSION = 0x0401
        private const val SOFTWARE_VERSION_2_1 = 0x0201
        private const val CONFIG_CODE_FULL = 2
        private const val BLUETOOTH_APDU_SIZE = 0x00200000
        private const val BLUETOOTH_SSDU_SIZE = 0xfffb
        private const val MAX_SESSIONS = 1022
        private const val SESSION_TIMEOUT_MS = 10_000
        private const val TRANSPORT_MODE_RELIABLE = 2
        private const val TRANSPORT_WINDOW_SIZE = 10
        private const val CONNECTIONLESS_MODE = 1
        private const val PROVIDED_SERVICE_PROFILE = "SWatch"
        private const val MAX_ID_BYTES = 32
        private const val MIN_FULL_MESSAGE_SIZE = 24
        private const val CONFIRM_MESSAGE_SIZE = 6
        private const val STRING_TERMINATOR: Byte = 0x3b
    }
}

data class SapPeerIdentity(
    val productId: String,
    val manufacturerId: String,
    val friendlyName: String
)

data class SapPeerDescriptionMessage(
    val messageType: Int,
    val protocolVersion: Int,
    val softwareVersion: Int,
    val configCode: Int,
    val apduSize: Int,
    val ssduSize: Int,
    val maxSessions: Int,
    val sessionTimeoutMs: Int,
    val transportMode: Int,
    val transportWindowSize: Int,
    val connectionlessMode: Int,
    val productId: String,
    val manufacturerId: String,
    val friendlyName: String,
    val providedServiceProfile: String
)
