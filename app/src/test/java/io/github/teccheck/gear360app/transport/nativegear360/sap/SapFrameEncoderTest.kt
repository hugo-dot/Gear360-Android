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
}
