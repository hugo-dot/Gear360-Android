package io.github.teccheck.gear360app.transport.nativegear360.sap

object SapProtocol {
    const val HEADER_SIZE = 2
    const val SESSION_ID_CAPEX = 1020
    const val SESSION_ID_SERVICE_CONNECTION = 1023
    const val MAX_BT_PAYLOAD_LENGTH = 12_275

    fun composePayload(
        sessionId: Int,
        frameType: SapFrameType,
        payload: ByteArray,
        fragmentation: Int = 0,
        sequenceNumber: Int? = null
    ): ByteArray {
        require(sessionId in 0..0x3ff) { "SAP session id out of range: $sessionId" }
        require(fragmentation in 0..0x03) { "SAP fragmentation out of range: $fragmentation" }

        val sequenceSize = if (sequenceNumber == null) 0 else 2
        val out = ByteArray(HEADER_SIZE + sequenceSize + payload.size)
        val frameBit = when (frameType) {
            SapFrameType.DATA -> 0
            SapFrameType.CONTROL -> 1
            SapFrameType.DEVICE -> error("Device packets do not use a SAP protocol header")
        }

        out[0] = (((frameBit shl 4) and 0x10) or ((sessionId and 0x3c0) ushr 6)).toByte()
        out[1] = ((((sessionId and 0x3f) shl 2) and 0xfc) or (fragmentation and 0x03)).toByte()

        var offset = HEADER_SIZE
        if (sequenceNumber != null) {
            SapCrc.writeUInt16(sequenceNumber, out, offset)
            offset += 2
        }
        payload.copyInto(out, offset)
        return out
    }

    fun parsePayload(rawPayload: ByteArray): ParsedSapPayload? {
        if (rawPayload.size < HEADER_SIZE) return null

        val version = (rawPayload[0].toInt() and 0xe0) ushr 5
        if (version != 0) return null

        val frameType = when ((rawPayload[0].toInt() and 0x10) ushr 4) {
            0 -> SapFrameType.DATA
            1 -> SapFrameType.CONTROL
            else -> return null
        }
        val sessionId =
            ((rawPayload[0].toInt() and 0x0f) shl 6) or
                ((rawPayload[1].toInt() and 0xfc) ushr 2)
        val fragmentation = rawPayload[1].toInt() and 0x03
        val payload = rawPayload.copyOfRange(HEADER_SIZE, rawPayload.size)
        return ParsedSapPayload(
            sessionId = sessionId,
            frameType = frameType,
            fragmentation = fragmentation,
            sequenceNumber = null,
            payload = payload
        )
    }
}

data class ParsedSapPayload(
    val sessionId: Int,
    val frameType: SapFrameType,
    val fragmentation: Int,
    val sequenceNumber: Int?,
    val payload: ByteArray
)
