package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SapFrameEncoderTest {
    @Test
    fun encoderProducesLengthPrefixedSapPayload() {
        val encoded = SapFrameEncoder().encode(
            SapFrame(
                channel = 204,
                sessionId = 11,
                payload = "{}".encodeToByteArray(),
                raw = byteArrayOf()
            )
        )

        assertEquals(6, encoded.size)
        assertEquals(4, SapCrc.readUInt16(encoded, 0))
    }

    @Test
    fun encodedFramesCanBeDecodedAgain() {
        val payload = """{"msgId":"shot-req"}""".encodeToByteArray()
        val encoded = SapFrameEncoder().encodeData(
            sessionId = 11,
            payload = payload,
            crcMode = SapTransportCrcMode.ENABLED
        )

        val result = SapFrameDecoder().append(encoded)

        assertTrue(result is SapDecodeResult.Frames)
        val frame = (result as SapDecodeResult.Frames).frames.single()
        assertEquals(11, frame.sessionId)
        assertEquals(SapTransportCrcMode.ENABLED, frame.transportCrcMode)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun legacyCapexQueryMatchesSamsungWireLayout() {
        val payload = SapCapabilityExchange.composeLegacyQuery()
        val encoded = SapFrameEncoder().encodeData(
            sessionId = SapProtocol.SESSION_ID_CAPEX,
            payload = payload
        )

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x18, 0x0f, 0xf0.toByte(),
                0x05, 0x01, 0x05, 0xa0.toByte(),
                0x2f, 0x73, 0x79, 0x73, 0x74, 0x65, 0x6d, 0x2f,
                0x44, 0x49, 0x5f, 0x33, 0x36, 0x30, 0x5f, 0x32, 0x44,
                0x3b
            ),
            encoded
        )
    }

    @Test
    fun initialCapexSyncQueryMatchesSamsungWireLayout() {
        val payload = SapCapabilityExchange.composeSyncQuery()
        val encoded = SapFrameEncoder().encodeData(
            sessionId = SapProtocol.SESSION_ID_CAPEX,
            payload = payload
        )

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x17, 0x0f, 0xf0.toByte(),
                0x01, 0x03,
                0x01,
                0x2f, 0x73, 0x79, 0x73, 0x74, 0x65, 0x6d, 0x2f,
                0x44, 0x49, 0x5f, 0x33, 0x36, 0x30, 0x5f, 0x32, 0x44,
                0x3b
            ),
            encoded
        )
    }

    @Test
    fun capexSyncResponseMatchesSamsungWireLayout() {
        val response = SapCapabilityExchange.composeResponse(
            SapCapabilityExchange.composeSyncQuery(), registryChecksum = 0x12345678
        )

        assertArrayEquals(
            byteArrayOf(
                0x02, 0x03, 0x12, 0x34, 0x56, 0x78, 0x00, 0x01,
                0x00, 0x01,
                0x44, 0x49, 0x5f, 0x33, 0x36, 0x30, 0x5f, 0x32,
                0x44, 0x41, 0x70, 0x70, 0x3b,
                0x00, 0x01,
                0x00, 0x01,
                0x2f, 0x73, 0x79, 0x73, 0x74, 0x65, 0x6d, 0x2f,
                0x44, 0x49, 0x5f, 0x33, 0x36, 0x30, 0x5f, 0x32, 0x44,
                0x3b,
                0x01, 0x00,
                0x01,
                0x00, 0x0a
            ),
            response
        )
    }

    @Test
    fun authenticationDevicePacketRoundTripsWithoutSapSessionHeader() {
        val payload = SapAccessoryAuthentication(
            messageType = SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_RESPONSE,
            authenticationType = 0,
            securityPacket = byteArrayOf(0x00, 0x00, 0x05, 0x12, 0x34)
        ).compose()

        val encoded = SapFrameEncoder().encodeDevicePacket(payload)
        val result = SapFrameDecoder().append(encoded)

        assertEquals(payload.size, SapCrc.readUInt16(encoded, 0))
        assertTrue(result is SapDecodeResult.Frames)
        val decoded = (result as SapDecodeResult.Frames).frames.single()
        assertEquals(SapFrameType.DEVICE, decoded.frameType)
        assertArrayEquals(payload, decoded.payload)
    }
}
