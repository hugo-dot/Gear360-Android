package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.*
import org.junit.Test

class NativeServiceNegotiationTest {
    private val writes = mutableListOf<ByteArray>()
    private val errors = mutableListOf<String>()
    private var channelOpened = false
    private val session = NativeSapSession(
        writer = { writes += it; true },
        peerIdentity = SapPeerIdentity("SM-A055F", "Samsung", "Gear360App"),
        securityServerId = "34:2D:0D:94:7F:29", securityClientId = "CC:E9:FA:67:51:1B",
        authenticatorFactory = { _, _ -> object : SapClientAuthenticator {
            override fun answerChallenge(clientChallenge: ByteArray) = byteArrayOf(0, 0, 5, 1, 2)
            override fun verifyConfirmation(clientResponse: ByteArray) = Unit
            override fun close() = Unit
        } },
        listener = object : NativeSapSession.Listener {
            override fun onPhaseChanged(phase: SapHandshakePhase, detail: String) = Unit
            override fun onFrameRx(frame: SapFrame) = Unit
            override fun onFrameTx(channel: Int, sessionId: Int, wireLength: Int) = Unit
            override fun onCapex(description: String) = Unit
            override fun onServiceConnectionRequest(request: ServiceConnectionRequest) = Unit
            override fun onChannelsOpen(channels: List<SapChannel>) = Unit
            override fun onChannel204Open() { channelOpened = true }
            override fun onChannelPayload(channel: Int, sessionId: Int, length: Int) = Unit
            override fun onProtocolEvent(message: String) = Unit
            override fun onProtocolError(message: String) { errors += message }
        }
    )

    @Test fun legacyAuthenticationAndCapexLeadToOneOutgoingServiceRequest() {
        establishCapabilities()
        val before = writes.size
        session.handleFrame(frame(1, peerCapabilities()))
        session.handleFrame(frame(1, peerCapabilities()))
        assertEquals(before + 1, writes.size)
        val request = pendingRequest()
        assertEquals(0x1234, request.acceptorId)
        assertEquals(1, request.initiatorId)
        assertEquals(listOf(204, 222, 230), request.channels.map { it.channelId })
        assertEquals(listOf(2, 3, 4), request.sessionIds)
        assertEquals(QosRecord(4, 0, 2), request.channels.first().qos)
        assertFalse(channelOpened)
        assertFalse(session.send(204, "{}".encodeToByteArray()))

        session.handleFrame(frame(1023, SapServiceConnection.composeAcceptedResponse(request)))
        assertTrue(channelOpened)
        assertTrue(errors.isEmpty())
        val json = "{\"msgId\":\"widget-info-req\"}".encodeToByteArray()
        assertTrue(session.send(204, json))
        assertEquals(2, decode(writes.last()).sessionId)
        assertArrayEquals(json, decode(writes.last()).payload)
        session.close()
        assertFalse(session.send(204, json))
        assertFalse(session.openChannel(204))
    }

    @Test fun mismatchedServiceResponseCannotOpenChannel() {
        establishCapabilities()
        session.handleFrame(frame(1, peerCapabilities()))
        val response = SapServiceConnection.composeAcceptedResponse(pendingRequest().copy(acceptorId = 99))
        session.handleFrame(frame(1023, response))
        assertFalse(channelOpened)
        assertEquals(SapSessionState.ERROR, session.state())
        assertTrue(errors.single().contains("mismatched"))
    }

    @Test fun timeoutAndLateResponseCannotProduceReady() {
        establishCapabilities()
        session.handleFrame(frame(1, peerCapabilities()))
        val response = SapServiceConnection.composeAcceptedResponse(pendingRequest())
        session.onHandshakeTimeout()
        session.handleFrame(frame(1023, response))
        assertFalse(channelOpened)
        assertFalse(session.send(204, byteArrayOf(1)))
        assertEquals(SapSessionState.ERROR, session.state())
    }

    @Test fun unsolicitedCapabilitiesBeforeAuthenticationCannotInitiateService() {
        session.onRfcommConnected()
        session.handleFrame(frame(1020, peerCapabilities()))
        assertTrue(writes.isEmpty())
        assertFalse(channelOpened)
    }

    @Test fun unreachableCameraEndsAttemptWithoutOpeningChannels() {
        session.onHandshakeTimeout("RFCOMM retry limit reached")
        assertEquals(SapSessionState.ERROR, session.state())
        assertEquals(listOf("RFCOMM retry limit reached"), errors)
        assertFalse(session.openChannel(204))
        assertFalse(session.send(204, byteArrayOf(1)))
        assertTrue(writes.isEmpty())
        session.onRfcommConnected()
        assertEquals(SapSessionState.HANDSHAKING, session.state())
        assertFalse(session.openChannel(204))
    }

    private fun establishCapabilities() {
        session.onRfcommConnected()
        val pd = hex("05 02 01 02 01 02 00 0F FA AA F0 AA 03 FE FF FF 02 00 0A 01 " +
            "53 62 61 6E 64 2B 3B 53 41 4D 53 55 4E 47 3B 53 68 65 61 6C 74 68 3B 2F 53 62 61 6E 64 2B 3B")
        session.handleFrame(frame(-1, pd, SapFrameType.DEVICE))
        for (type in listOf(0x10, 0x12)) {
            session.handleFrame(frame(-1, SapAccessoryAuthentication(type, 0, byteArrayOf(0, 0, 5, 1, 2)).compose(), SapFrameType.DEVICE))
        }
        val capex = decode(writes.last())
        assertEquals(SapTransportCrcMode.ENABLED, capex.transportCrcMode)
        assertEquals(1023, capex.sessionId)
        val request = SapServiceConnection.parseRequest(capex.payload)!!
        assertEquals(SapCapabilityExchange.SERVICE_PROFILE, request.profileId)
        session.handleFrame(frame(1023, SapServiceConnection.composeAcceptedResponse(request)))
        assertArrayEquals(SapCapabilityExchange.composeSyncQuery(), decode(writes.last()).payload)
        assertFalse(channelOpened)
    }

    private fun peerCapabilities(): ByteArray = SapCapabilityExchange.composeResponse(
        SapCapabilityExchange.composeSyncQuery(), friendlyName = "SM-R210"
    ).also {
        val componentOffset = 8 + 2 + "SM-R210".length + 1 + 2
        SapCrc.writeUInt16(0x1234, it, componentOffset)
        it[it.size - 3] = 1 // SDK consumer role; phone provider role is zero.
    }

    private fun pendingRequest() = SapServiceConnection.parseRequest(decode(writes.last()).payload)!!
    private fun frame(id: Int, bytes: ByteArray, type: SapFrameType = SapFrameType.DATA) = SapFrame(
        channel = id, sessionId = id, frameType = type, payload = bytes, raw = byteArrayOf(),
        transportCrcMode = if (id >= 0) SapTransportCrcMode.ENABLED else SapTransportCrcMode.DISABLED
    )
    private fun decode(bytes: ByteArray) = (SapFrameDecoder().append(bytes) as SapDecodeResult.Frames).frames.single()
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
