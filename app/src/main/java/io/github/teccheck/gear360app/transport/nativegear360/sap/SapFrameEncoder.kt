package io.github.teccheck.gear360app.transport.nativegear360.sap

class SapFrameEncoder(
    private val defaultCrcMode: SapTransportCrcMode = SapTransportCrcMode.DISABLED
) {
    fun encode(frame: SapFrame): ByteArray {
        if (frame.frameType == SapFrameType.DEVICE) {
            return encodeDevicePacket(frame.payload, frame.transportCrcMode)
        }
        return encodeData(
            sessionId = frame.sessionId,
            payload = frame.payload,
            frameType = frame.frameType,
            fragmentation = frame.fragmentation,
            sequenceNumber = frame.sequenceNumber,
            crcMode = frame.transportCrcMode
        )
    }

    fun encodeData(
        sessionId: Int,
        payload: ByteArray,
        frameType: SapFrameType = SapFrameType.DATA,
        fragmentation: Int = 0,
        sequenceNumber: Int? = null,
        crcMode: SapTransportCrcMode = defaultCrcMode
    ): ByteArray {
        require(frameType != SapFrameType.DEVICE) {
            "Use encodeDevicePacket for Samsung Accessory device packets"
        }
        val sapPayload = SapProtocol.composePayload(
            sessionId = sessionId,
            frameType = frameType,
            payload = payload,
            fragmentation = fragmentation,
            sequenceNumber = sequenceNumber
        )
        return encodeTransportPayload(sapPayload, crcMode)
    }

    fun encodeDevicePacket(
        payload: ByteArray,
        crcMode: SapTransportCrcMode = defaultCrcMode
    ): ByteArray {
        requireNotNull(SapAccessoryAuthentication.parse(payload)) {
            "Invalid Samsung Accessory authentication device packet"
        }
        return encodeTransportPayload(payload, crcMode)
    }

    fun encodeTransportPayload(
        sapPayload: ByteArray,
        crcMode: SapTransportCrcMode = defaultCrcMode
    ): ByteArray {
        require(sapPayload.size in 1..SapProtocol.MAX_BT_PAYLOAD_LENGTH) {
            "SAP payload length out of range: ${sapPayload.size}"
        }

        return when (crcMode) {
            SapTransportCrcMode.DISABLED -> {
                ByteArray(2 + sapPayload.size).also {
                    SapCrc.writeUInt16(sapPayload.size, it, 0)
                    sapPayload.copyInto(it, 2)
                }
            }

            SapTransportCrcMode.ENABLED -> {
                ByteArray(2 + 2 + sapPayload.size + 2).also {
                    SapCrc.writeUInt16(sapPayload.size, it, 0)
                    SapCrc.writeUInt16(SapCrc.compute(it, 0, 2), it, 2)
                    sapPayload.copyInto(it, 4)
                    SapCrc.writeUInt16(SapCrc.compute(sapPayload), it, 4 + sapPayload.size)
                }
            }
        }
    }
}
