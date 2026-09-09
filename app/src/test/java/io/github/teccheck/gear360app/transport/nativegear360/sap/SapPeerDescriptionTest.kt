package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SapPeerDescriptionTest {
    private val codec = SapPeerDescription(
        SapPeerIdentity(
            productId = "SM-A055F",
            manufacturerId = "samsung",
            friendlyName = "Galaxy A05"
        )
    )

    @Test
    fun offerNegotiatesGear360Protocol21() {
        val offer = codec.composeOffer(0x0201)
        val parsed = codec.parse(offer)

        assertNotNull(parsed)
        assertEquals(SapPeerDescription.MESSAGE_OFFER, parsed!!.messageType)
        assertEquals(0x0201, parsed.protocolVersion)
        assertEquals(2, parsed.configCode)
        assertEquals(0x00200000, parsed.apduSize)
        assertEquals(0xfffb, parsed.ssduSize)
        assertEquals(1022, parsed.maxSessions)
        assertEquals("SM-A055F", parsed.productId)
        assertEquals("samsung", parsed.manufacturerId)
        assertEquals("Galaxy A05", parsed.friendlyName)
        assertEquals("SWatch", parsed.providedServiceProfile)
    }

    @Test
    fun offerRoundTripsThroughTransportFrame() {
        val payload = codec.composeOffer(0x0201)
        val wire = SapFrameEncoder().encodeData(sessionId = 320, payload = payload)
        val decoded = SapFrameDecoder().append(wire) as SapDecodeResult.Frames

        assertEquals(1, decoded.frames.size)
        assertEquals(320, decoded.frames.single().sessionId)
        assertEquals(payload.toList(), decoded.frames.single().payload.toList())
    }

    @Test
    fun confirmUsesSamsungCompactSixByteLayout() {
        codec.composeOffer(0x0201)

        val confirm = codec.composeConfirm()

        assertArrayEquals(
            byteArrayOf(0x04, 0x02, 0x01, 0x02, 0x01, 0x00),
            confirm
        )
        assertEquals(0, codec.confirmStatus(confirm))
    }
}
