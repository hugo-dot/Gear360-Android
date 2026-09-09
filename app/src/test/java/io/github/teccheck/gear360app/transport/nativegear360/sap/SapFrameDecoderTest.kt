package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SapFrameDecoderTest {
    @Test
    fun rawGear360JsonIsNotTreatedAsSapChannel204() {
        val decoder = SapFrameDecoder()

        val result = decoder.append("""{"msgId":"shot-req"}""".encodeToByteArray())

        assertTrue(result is SapDecodeResult.UnknownFraming)
        assertEquals(
            """{"msgId":"shot-req"}""".encodeToByteArray().size,
            (result as SapDecodeResult.UnknownFraming).bufferedBytes
        )
    }

    @Test
    fun shortSamplesNeedMoreData() {
        val decoder = SapFrameDecoder()

        val result = decoder.append(byteArrayOf(0x01, 0x02))

        assertEquals(SapDecodeResult.NeedMoreData, result)
    }

    @Test
    fun parserHandlesFragmentedFrames() {
        val payload = """{"msgId":"date-time-req"}""".encodeToByteArray()
        val encoded = SapFrameEncoder().encodeData(sessionId = 42, payload = payload)
        val decoder = SapFrameDecoder()

        assertEquals(SapDecodeResult.NeedMoreData, decoder.append(encoded.copyOfRange(0, 3)))
        val result = decoder.append(encoded.copyOfRange(3, encoded.size))

        assertTrue(result is SapDecodeResult.Frames)
        val frame = (result as SapDecodeResult.Frames).frames.single()
        assertEquals(42, frame.sessionId)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun parserHandlesMultipleFramesInOneRead() {
        val firstPayload = "one".encodeToByteArray()
        val secondPayload = "two".encodeToByteArray()
        val encoder = SapFrameEncoder()
        val combined =
            encoder.encodeData(sessionId = 1, payload = firstPayload) +
                encoder.encodeData(sessionId = 2, payload = secondPayload)

        val result = SapFrameDecoder().append(combined)

        assertTrue(result is SapDecodeResult.Frames)
        val frames = (result as SapDecodeResult.Frames).frames
        assertEquals(2, frames.size)
        assertEquals(1, frames[0].sessionId)
        assertArrayEquals(firstPayload, frames[0].payload)
        assertEquals(2, frames[1].sessionId)
        assertArrayEquals(secondPayload, frames[1].payload)
    }

    @Test
    fun parserRecognizesFragmentedSamsungAuthenticationDevicePacket() {
        val securityPacket = byteArrayOf(0x00, 0x00, 0x05, 0x55, 0x66)
        val payload = SapAccessoryAuthentication(
            messageType = SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_REQUEST,
            authenticationType = 0,
            securityPacket = securityPacket
        ).compose()
        val wire = SapFrameEncoder().encodeDevicePacket(payload)
        val decoder = SapFrameDecoder()

        assertEquals(SapDecodeResult.NeedMoreData, decoder.append(wire.copyOfRange(0, 4)))
        val result = decoder.append(wire.copyOfRange(4, wire.size))

        assertTrue(result is SapDecodeResult.Frames)
        val frame = (result as SapDecodeResult.Frames).frames.single()
        assertEquals(SapFrameType.DEVICE, frame.frameType)
        assertEquals(-1, frame.sessionId)
        assertArrayEquals(payload, frame.payload)
    }
}
