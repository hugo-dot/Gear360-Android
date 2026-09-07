package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SapServiceConnectionTest {
    @Test
    fun parsesGear360StyleServiceConnectionRequest() {
        val request = SapServiceConnection.parseRequest(sampleRequest())

        assertNotNull(request)
        requireNotNull(request)
        assertEquals(SapHandshake.PROFILE_ID, request.profileId)
        assertEquals(listOf(11, 12, 13), request.sessionIds)
        assertEquals(listOf(204, 222, 230), request.channels.map { it.channelId })
    }

    @Test
    fun acceptedResponseKeepsSessionIds() {
        val request = requireNotNull(SapServiceConnection.parseRequest(sampleRequest()))
        val response = SapServiceConnection.parseResponse(
            SapServiceConnection.composeAcceptedResponse(request)
        )

        assertNotNull(response)
        requireNotNull(response)
        assertEquals(SapServiceConnection.STATUS_ACCEPTED, response.statusCode)
        assertEquals(request.profileId, response.profileId)
        assertEquals(request.sessionIds, response.sessionIds)
    }

    @Test
    fun legacyCapexResponseAdvertisesGear360Profile() {
        val response = SapCapabilityExchange.composeLegacyResponse()

        assertEquals(SapCapabilityExchange.MESSAGE_TYPE_LEGACY_RESPONSE, response[0].toInt())
        assertTrue(response.decodeToString().contains(SapHandshake.PROFILE_ID))
        assertTrue(response.decodeToString().contains("DI_360_2DApp"))
    }

    @Test
    fun profileCodecUsesSamsungFixedSeventeenByteProfileIds() {
        val encoded = SapProfileIdCodec.encode(SapHandshake.PROFILE_ID)
        val decoded = SapProfileIdCodec.read(encoded, 0)

        assertEquals(17, encoded.size)
        assertEquals(SapHandshake.PROFILE_ID, decoded?.value)
        assertEquals(17, decoded?.nextOffset)
    }

    private fun sampleRequest(): ByteArray {
        val profile = SapProfileIdCodec.encode(SapHandshake.PROFILE_ID)
        val out = ByteArray(1 + 2 + 2 + profile.size + 2 + 3 * 2 + 3 * 2 + 3 * 3 + 3)
        var offset = 0
        out[offset++] = SapServiceConnection.MESSAGE_TYPE_CREATION_REQUEST.toByte()
        SapCrc.writeUInt16(1, out, offset)
        offset += 2
        SapCrc.writeUInt16(2, out, offset)
        offset += 2
        profile.copyInto(out, offset)
        offset += profile.size
        SapCrc.writeUInt16(3, out, offset)
        offset += 2
        listOf(11, 12, 13).forEach {
            SapCrc.writeUInt16(it, out, offset)
            offset += 2
        }
        listOf(204, 222, 230).forEach {
            SapCrc.writeUInt16(it, out, offset)
            offset += 2
        }
        repeat(3) {
            out[offset++] = 0
            out[offset++] = 0
            out[offset++] = 0
        }
        repeat(3) {
            out[offset++] = 1
        }
        return out
    }
}
