package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SapChannelMuxTest {
    @Test
    fun payloadsAreReleasedOnlyForNegotiatedChannels() {
        val mux = SapChannelMux()
        val frame = SapFrame(
            channel = 11,
            sessionId = 11,
            payload = "{}".encodeToByteArray(),
            raw = byteArrayOf(0x01, 0x02)
        )

        assertFalse(mux.isOpen(204))
        assertNull(mux.toPayload(frame))

        mux.bind(
            ServiceConnectionRequest(
                acceptorId = 1,
                initiatorId = 2,
                profileId = SapHandshake.PROFILE_ID,
                sessionIds = listOf(11),
                channels = listOf(
                    ServiceChannelRecord(
                        channelId = 204,
                        sessionId = 11,
                        qos = QosRecord(type = 0, dataRate = 0, classType = 0),
                        payloadType = 1
                    )
                )
            )
        )

        assertTrue(mux.isOpen(204))
        assertEquals(11, mux.sessionForChannel(204))
        assertEquals(204, mux.channelForSession(11))
        assertNotNull(mux.toPayload(frame))
    }
}
