package io.github.teccheck.gear360app.transport.nativegear360.sap

import io.github.teccheck.gear360app.transport.nativegear360.toHexString

class SapFrameDecoder(
    private val maxPayloadLength: Int = SapProtocol.MAX_BT_PAYLOAD_LENGTH,
    private val detectPeerDescription: Boolean = false
) {
    private var buffer = ByteArray(0)
    var peerDescriptionEnabled = detectPeerDescription

    fun append(data: ByteArray): SapDecodeResult {
        if (data.isEmpty()) return SapDecodeResult.NeedMoreData

        buffer += data
        val frames = mutableListOf<SapFrame>()

        while (true) {
            if (buffer.size < 2) {
                return framesOrNeedMore(frames)
            }

            val payloadLength = SapCrc.readUInt16(buffer, 0)
            if (payloadLength <= 0 || payloadLength > maxPayloadLength) {
                return failAndReset(buffer.size, "invalid payload length=$payloadLength")
            }

            val crcEnabled = hasValidLengthCrc(payloadLength)
            val headerSize = if (crcEnabled) 4 else 2
            val payloadCrcSize = if (crcEnabled) 2 else 0
            val totalSize = headerSize + payloadLength + payloadCrcSize

            if (buffer.size < totalSize) {
                return framesOrNeedMore(frames)
            }

            val raw = buffer.copyOfRange(0, totalSize)
            val sapPayload = buffer.copyOfRange(headerSize, headerSize + payloadLength)

            if (crcEnabled) {
                val expected = SapCrc.readUInt16(buffer, headerSize + payloadLength)
                val actual = SapCrc.compute(sapPayload)
                if (expected != actual) {
                    return failAndReset(
                        totalSize,
                        "payload CRC mismatch expected=%04X actual=%04X".format(expected, actual)
                    )
                }
            }

            val transportCrcMode = if (crcEnabled) {
                SapTransportCrcMode.ENABLED
            } else {
                SapTransportCrcMode.DISABLED
            }
            val authentication = SapAccessoryAuthentication.parse(sapPayload)
            if (authentication != null ||
                (peerDescriptionEnabled && SapPeerDescription.isLegacyDevicePacket(sapPayload))) {
                frames += SapFrame(
                    channel = DEVICE_CHANNEL,
                    sessionId = DEVICE_CHANNEL,
                    frameType = SapFrameType.DEVICE,
                    transportCrcMode = transportCrcMode,
                    payload = sapPayload,
                    raw = raw
                )
            } else {
                val parsed = SapProtocol.parsePayload(sapPayload)
                    ?: return failAndReset(totalSize, "unsupported SAP protocol header")

                frames += SapFrame(
                    channel = parsed.sessionId,
                    sessionId = parsed.sessionId,
                    frameType = parsed.frameType,
                    fragmentation = parsed.fragmentation,
                    sequenceNumber = parsed.sequenceNumber,
                    transportCrcMode = transportCrcMode,
                    payload = parsed.payload,
                    raw = raw
                )
            }

            buffer = buffer.copyOfRange(totalSize, buffer.size)
        }
    }

    fun reset() {
        buffer = ByteArray(0)
        peerDescriptionEnabled = detectPeerDescription
    }

    private fun hasValidLengthCrc(payloadLength: Int): Boolean {
        if (buffer.size < 4) return false
        val expected = SapCrc.readUInt16(buffer, 2)
        val actual = SapCrc.compute(SapCrc.uint16(payloadLength))
        return expected == actual
    }

    private fun framesOrNeedMore(frames: List<SapFrame>): SapDecodeResult {
        return if (frames.isEmpty()) {
            SapDecodeResult.NeedMoreData
        } else {
            SapDecodeResult.Frames(frames)
        }
    }

    private fun failAndReset(bufferedBytes: Int, reason: String): SapDecodeResult.UnknownFraming {
        val sample = buffer.copyOfRange(0, minOf(buffer.size, 128)).toHexString()
        buffer = ByteArray(0)
        return SapDecodeResult.UnknownFraming(
            bufferedBytes = bufferedBytes,
            sampleHex = sample,
            reason = reason
        )
    }

    private companion object {
        const val DEVICE_CHANNEL = -1
    }
}
