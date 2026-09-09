package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSapSessionTest {
    @Test
    fun acceptedPeerDescriptionStartsSamsungCapexSync() {
        val writes = mutableListOf<ByteArray>()
        val phases = mutableListOf<SapHandshakePhase>()
        val session = NativeSapSession(
            writer = { wire ->
                writes += wire
                true
            },
            listener = RecordingListener(phases),
            peerIdentity = PHONE_IDENTITY
        )

        session.onRfcommConnected()
        session.handleFrame(
            dataFrame(
                sessionId = PEER_DESCRIPTION_SESSION,
                payload = cameraPeerDescription(SapPeerDescription.MESSAGE_PROBE)
            )
        )

        assertEquals(1, writes.size)
        val offer = decodeSingle(writes[0])
        assertEquals(PEER_DESCRIPTION_SESSION, offer.sessionId)
        assertEquals(SapPeerDescription.MESSAGE_OFFER, offer.payload[0].toInt() and 0xff)

        session.handleFrame(
            dataFrame(
                sessionId = PEER_DESCRIPTION_SESSION,
                payload = byteArrayOf(0x04, 0x02, 0x01, 0x02, 0x01, 0x00)
            )
        )

        assertEquals(2, writes.size)
        val capex = decodeSingle(writes[1])
        assertEquals(SapProtocol.SESSION_ID_CAPEX, capex.sessionId)
        assertArrayEquals(SapCapabilityExchange.composeSyncQuery(), capex.payload)
        assertTrue(phases.contains(SapHandshakePhase.CAPABILITY_EXCHANGE))
    }

    @Test
    fun wsmAuthenticationIsCompletedBeforePeerDescriptionContinues() {
        val writes = mutableListOf<ByteArray>()
        val phases = mutableListOf<SapHandshakePhase>()
        val fakeAuthenticator = RecordingAuthenticator()
        val session = NativeSapSession(
            writer = { wire -> writes += wire; true },
            listener = RecordingListener(phases),
            peerIdentity = PHONE_IDENTITY,
            securityServerId = "34:2D:0D:94:7F:29",
            securityClientId = "CC:E9:FA:67:51:1B",
            authenticatorFactory = { _, _ -> fakeAuthenticator }
        )
        val challenge = byteArrayOf(0x00, 0x00, 0x05, 0x01, 0x02)

        session.onRfcommConnected()
        session.handleFrame(
            dataFrame(
                sessionId = PEER_DESCRIPTION_SESSION,
                payload = cameraPeerDescription(SapPeerDescription.MESSAGE_PROBE)
            )
        )
        session.handleFrame(deviceAuthenticationFrame(
            SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_REQUEST,
            challenge
        ))

        assertTrue(fakeAuthenticator.challengeReceived)
        assertEquals(SapFrameType.DEVICE, decodeSingle(writes[1]).frameType)
        assertTrue(phases.contains(SapHandshakePhase.AUTHENTICATION))

        session.handleFrame(deviceAuthenticationFrame(
            SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_CONFIRM,
            challenge
        ))
        assertTrue(fakeAuthenticator.confirmationReceived)
        assertEquals(SapHandshakePhase.PEER_DESCRIPTION, phases.last())
    }

    private fun cameraPeerDescription(messageType: Int): ByteArray {
        return SapPeerDescription(CAMERA_IDENTITY)
            .composeOffer(0x0201)
            .also { it[0] = messageType.toByte() }
    }

    private fun dataFrame(sessionId: Int, payload: ByteArray) = SapFrame(
        channel = sessionId,
        sessionId = sessionId,
        payload = payload,
        raw = byteArrayOf()
    )

    private fun deviceAuthenticationFrame(messageType: Int, securityPacket: ByteArray): SapFrame {
        val payload = SapAccessoryAuthentication(messageType, 0, securityPacket).compose()
        return SapFrame(
            channel = -1,
            sessionId = -1,
            frameType = SapFrameType.DEVICE,
            payload = payload,
            raw = byteArrayOf()
        )
    }

    private fun decodeSingle(wire: ByteArray): SapFrame {
        val result = SapFrameDecoder().append(wire)
        assertTrue(result is SapDecodeResult.Frames)
        return (result as SapDecodeResult.Frames).frames.single()
    }

    private class RecordingListener(
        private val phases: MutableList<SapHandshakePhase>
    ) : NativeSapSession.Listener {
        override fun onPhaseChanged(phase: SapHandshakePhase, detail: String) {
            phases += phase
        }

        override fun onFrameRx(frame: SapFrame) = Unit
        override fun onFrameTx(channel: Int, sessionId: Int, wireLength: Int) = Unit
        override fun onCapex(description: String) = Unit
        override fun onServiceConnectionRequest(request: ServiceConnectionRequest) = Unit
        override fun onChannelsOpen(channels: List<SapChannel>) = Unit
        override fun onChannel204Open() = Unit
        override fun onChannelPayload(channel: Int, sessionId: Int, length: Int) = Unit
        override fun onProtocolEvent(message: String) = Unit
        override fun onProtocolError(message: String) = Unit
    }

    private class RecordingAuthenticator : SapClientAuthenticator {
        var challengeReceived = false
        var confirmationReceived = false

        override fun answerChallenge(clientChallenge: ByteArray): ByteArray {
            challengeReceived = true
            return byteArrayOf(0x00, 0x00, 0x05, 0x03, 0x04)
        }

        override fun verifyConfirmation(clientResponse: ByteArray) {
            confirmationReceived = true
        }

        override fun close() = Unit
    }

    private companion object {
        const val PEER_DESCRIPTION_SESSION = 320
        val PHONE_IDENTITY = SapPeerIdentity("SM-A055F", "Samsung", "Gear360App")
        val CAMERA_IDENTITY = SapPeerIdentity("SM-R210", "Samsung", "Gear 360")
    }
}
