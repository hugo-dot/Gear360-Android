package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.*
import org.junit.Test

class LegacyPeerDescriptionTest {
    // First inbound packet captured from the SM-R210 on 2026-09-10.
    private val wire = ("00 33 05 02 01 02 01 02 00 0F FA AA F0 AA 03 FE FF FF 02 00 0A 01 " +
        "53 62 61 6E 64 2B 3B 53 41 4D 53 55 4E 47 3B 53 68 65 61 6C 74 68 3B 2F 53 62 61 6E 64 2B 3B")
        .split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val pd = SapPeerDescription(SapPeerIdentity("SM-A055F", "Samsung", "Gear360App"))

    @Test fun capturedRequestKeepsItsDeviceHeaderWhenFragmented() {
        val decoder = SapFrameDecoder(detectPeerDescription = true)
        wire.dropLast(1).forEach { assertTrue(decoder.append(byteArrayOf(it)) is SapDecodeResult.NeedMoreData) }
        val frame = (decoder.append(wire.takeLast(1).toByteArray()) as SapDecodeResult.Frames).frames.single()
        assertEquals(SapFrameType.DEVICE, frame.frameType)
        val request = pd.parse(frame.payload)!!
        assertEquals(5, request.messageType)
        assertEquals("Sband+", request.productId)
        assertEquals("SAMSUNG", request.manufacturerId)
        assertEquals(0x000ffaaa, request.apduSize)
    }

    @Test fun responseIncludesStatusAndNoSessionHeader() {
        val request = pd.parse(wire.copyOfRange(2, wire.size))!!
        val response = pd.composeLegacyResponse(request)
        assertEquals(6, response[0].toInt())
        assertEquals(0, response[5].toInt())
        assertEquals(2, response[6].toInt())
        val decoded = pd.parse(response)!!
        assertEquals("SM-A055F", decoded.productId)
        assertEquals(request.apduSize, decoded.apduSize)
        assertEquals(request.ssduSize, decoded.ssduSize)
        val encoded = SapFrameEncoder().encodeDevicePacket(response)
        assertEquals(6, encoded[2].toInt())
        val frames = SapFrameDecoder(detectPeerDescription = true).append(encoded + wire) as SapDecodeResult.Frames
        assertEquals(2, frames.frames.size)
        assertArrayEquals(response, frames.frames[0].payload)
    }

    @Test fun establishedSessionDoesNotMistakeDataForPeerDescription() {
        val decoder = SapFrameDecoder(detectPeerDescription = true)
        decoder.peerDescriptionEnabled = false
        val frame = (decoder.append(wire) as SapDecodeResult.Frames).frames.single()
        assertEquals(SapFrameType.DATA, frame.frameType)
    }
}
