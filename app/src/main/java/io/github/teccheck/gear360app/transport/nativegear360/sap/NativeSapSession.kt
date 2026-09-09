package io.github.teccheck.gear360app.transport.nativegear360.sap

import android.util.Log
import io.github.teccheck.gear360app.transport.nativegear360.toHexString

private const val TAG_SAP = "G360-SAP"
private const val TAG_CH204 = "G360-CH204"

class NativeSapSession(
    private val writer: (ByteArray) -> Boolean,
    private val listener: Listener,
    peerIdentity: SapPeerIdentity,
    private val encoder: SapFrameEncoder = SapFrameEncoder(),
    private val securityServerId: String? = null,
    private val securityClientId: String? = null,
    private val authenticatorFactory: (String, String) -> SapClientAuthenticator =
        { serverId, clientId -> SamsungWsmClientAuthenticator(serverId, clientId) }
) : Gear360SapSession {
    private val mux = SapChannelMux()
    private val keepAlive = SapKeepAlive()
    private val peerDescription = SapPeerDescription(peerIdentity)
    @Volatile private var state = SapSessionState.HANDSHAKING
    @Volatile private var phase = SapHandshakePhase.RFCOMM_CONNECTED
    @Volatile private var crcMode = SapTransportCrcMode.DISABLED
    @Volatile private var peerDescriptionSessionId: Int? = null
    @Volatile private var waitingForPeerConfirm = false
    private var authenticator: SapClientAuthenticator? = null

    fun onRfcommConnected() {
        peerDescriptionSessionId = null
        waitingForPeerConfirm = false
        phase = SapHandshakePhase.PEER_DESCRIPTION
        listener.onPhaseChanged(
            phase,
            "RFCOMM connected; waiting for Gear360 peer-description message"
        )
    }

    fun handleFrame(frame: SapFrame): List<SapPayload> {
        keepAlive.markRx(System.currentTimeMillis())
        crcMode = frame.transportCrcMode
        listener.onFrameRx(frame)

        if (frame.frameType == SapFrameType.DEVICE) {
            handleAuthentication(frame)
            return emptyList()
        }

        if (frame.frameType == SapFrameType.CONTROL) {
            listener.onProtocolEvent(
                "control frame session=${frame.sessionId} len=${frame.payload.size} payload=${frame.payload.toHexString(64)}"
            )
            return emptyList()
        }

        if (isPeerDescriptionFrame(frame)) {
            handlePeerDescription(frame)
            return emptyList()
        }

        return when (frame.sessionId) {
            SapProtocol.SESSION_ID_CAPEX -> {
                handleCapabilityExchange(frame)
                emptyList()
            }

            SapProtocol.SESSION_ID_SERVICE_CONNECTION -> {
                handleServiceConnection(frame)
                emptyList()
            }

            else -> {
                val payload = mux.toPayload(frame)
                if (payload == null) {
                    listener.onProtocolEvent(
                        "unmapped SAP data session=${frame.sessionId} len=${frame.payload.size}"
                    )
                    emptyList()
                } else {
                    if (payload.channel == 204) {
                        Log.i(TAG_CH204, "RX 204 len=${payload.payload.size}")
                    }
                    listener.onChannelPayload(payload.channel, frame.sessionId, payload.payload.size)
                    listOf(payload)
                }
            }
        }
    }

    override fun state(): SapSessionState = state

    override fun openChannel(channel: Int): Boolean {
        return mux.isOpen(channel)
    }

    override fun send(channel: Int, payload: ByteArray): Boolean {
        val sessionId = mux.sessionForChannel(channel)
        if (sessionId == null) {
            listener.onProtocolEvent("TX refused channel=$channel; channel is not negotiated")
            return false
        }

        val encoded = encoder.encodeData(
            sessionId = sessionId,
            payload = payload,
            crcMode = crcMode
        )
        val written = writer(encoded)
        if (written) {
            keepAlive.markTx(System.currentTimeMillis())
            if (channel == 204) {
                Log.i(TAG_CH204, "TX 204 session=$sessionId len=${payload.size}")
            }
            listener.onFrameTx(channel, sessionId, encoded.size)
        } else {
            listener.onProtocolEvent("TX failed channel=$channel session=$sessionId")
        }
        return written
    }

    override fun close() {
        state = SapSessionState.CLOSED
        phase = SapHandshakePhase.RFCOMM_CONNECTED
        peerDescriptionSessionId = null
        waitingForPeerConfirm = false
        authenticator?.close()
        authenticator = null
    }

    private fun handleAuthentication(frame: SapFrame) {
        val message = SapAccessoryAuthentication.parse(frame.payload)
        if (message == null) {
            state = SapSessionState.ERROR
            listener.onProtocolError("Malformed Samsung Accessory authentication packet")
            return
        }

        phase = SapHandshakePhase.AUTHENTICATION
        listener.onPhaseChanged(
            phase,
            "WSM RX type=0x%02X authType=%d packetLen=%d".format(
                message.messageType,
                message.authenticationType,
                message.securityPacket.size
            )
        )

        try {
            when (message.messageType) {
                SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_REQUEST -> {
                    val serverId = securityServerId
                        ?: error("Gear360 Bluetooth address is unavailable for WSM")
                    val clientId = securityClientId
                        ?: error("Phone Bluetooth address is unavailable for WSM")
                    authenticator?.close()
                    val activeAuthenticator = authenticatorFactory(serverId, clientId)
                    authenticator = activeAuthenticator
                    val responsePacket = activeAuthenticator.answerChallenge(message.securityPacket)
                    val response = SapAccessoryAuthentication(
                        messageType = SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_RESPONSE,
                        authenticationType = message.authenticationType,
                        securityPacket = responsePacket
                    ).compose()
                    val wire = encoder.encodeDevicePacket(response, frame.transportCrcMode)
                    check(writer(wire)) { "WSM authentication response write failed" }
                    keepAlive.markTx(System.currentTimeMillis())
                    listener.onProtocolEvent(
                        "WSM TX type=0x11 authType=${message.authenticationType} packetLen=${responsePacket.size} wireLen=${wire.size}"
                    )
                }

                SapAccessoryAuthentication.ACCESSORY_AUTHENTICATE_CONFIRM -> {
                    val activeAuthenticator = authenticator
                        ?: error("WSM confirmation received before authentication request")
                    activeAuthenticator.verifyConfirmation(message.securityPacket)
                    activeAuthenticator.close()
                    authenticator = null
                    phase = SapHandshakePhase.PEER_DESCRIPTION
                    listener.onPhaseChanged(
                        phase,
                        "WSM authentication confirmed; waiting for peer-description confirmation"
                    )
                }

                else -> error(
                    "Unexpected WSM message type=0x%02X for phone client role".format(message.messageType)
                )
            }
        } catch (error: Throwable) {
            authenticator?.close()
            authenticator = null
            state = SapSessionState.ERROR
            listener.onProtocolError(
                "WSM authentication failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun isPeerDescriptionFrame(frame: SapFrame): Boolean {
        val existingSession = peerDescriptionSessionId
        if (existingSession != null) return frame.sessionId == existingSession && phase == SapHandshakePhase.PEER_DESCRIPTION
        if (phase != SapHandshakePhase.PEER_DESCRIPTION) return false
        return peerDescription.messageType(frame.payload) in setOf(
            SapPeerDescription.MESSAGE_PROBE,
            SapPeerDescription.MESSAGE_OFFER,
            SapPeerDescription.MESSAGE_COUNTER_OFFER,
            SapPeerDescription.MESSAGE_CONFIRM,
            SapPeerDescription.MESSAGE_REQUEST,
            SapPeerDescription.MESSAGE_RESPONSE,
            SapPeerDescription.MESSAGE_ERROR
        )
    }

    private fun handlePeerDescription(frame: SapFrame) {
        val messageType = peerDescription.messageType(frame.payload)
        val version = peerDescription.protocolVersion(frame.payload)
        peerDescriptionSessionId = frame.sessionId
        listener.onProtocolEvent(
            "PD RX session=${frame.sessionId} type=$messageType version=${version?.let { "0x%04X".format(it) } ?: "unknown"} len=${frame.payload.size}"
        )

        when (messageType) {
            SapPeerDescription.MESSAGE_PROBE -> {
                if (version == null) {
                    failPeerDescription("PD_PROBE is missing protocol version")
                    return
                }
                val parsed = peerDescription.parse(frame.payload)
                if (parsed != null) {
                    listener.onProtocolEvent(
                        "PD peer product=${parsed.productId} manufacturer=${parsed.manufacturerId} service=${parsed.providedServiceProfile}"
                    )
                } else {
                    listener.onProtocolEvent("PD_PROBE fixed identity could not be fully parsed; negotiating by version")
                }
                val offer = try {
                    peerDescription.composeOffer(version)
                } catch (e: IllegalArgumentException) {
                    failPeerDescription(e.message ?: "incompatible PD protocol")
                    return
                }
                if (writePeerDescription(frame.sessionId, offer, "PD_OFFER")) {
                    waitingForPeerConfirm = true
                }
            }

            SapPeerDescription.MESSAGE_COUNTER_OFFER -> {
                if (!waitingForPeerConfirm) {
                    failPeerDescription("unexpected PD_COUNTER_OFFER before PD_OFFER")
                    return
                }
                val confirm = peerDescription.composeConfirm()
                if (writePeerDescription(frame.sessionId, confirm, "PD_CONFIRM")) {
                    completePeerDescription("counter-offer accepted")
                }
            }

            SapPeerDescription.MESSAGE_CONFIRM -> {
                if (!waitingForPeerConfirm) {
                    failPeerDescription("unexpected PD_CONFIRM before PD_OFFER")
                    return
                }
                val status = peerDescription.confirmStatus(frame.payload)
                if (status != SapPeerDescription.STATUS_ACCEPTED) {
                    failPeerDescription("PD_CONFIRM rejected status=${status ?: "missing"}")
                    return
                }
                completePeerDescription("peer confirmation received")
            }

            SapPeerDescription.MESSAGE_ERROR -> failPeerDescription("peer returned PD_ERROR")
            else -> listener.onProtocolEvent("PD message type=$messageType observed; waiting for negotiation response")
        }
    }

    private fun writePeerDescription(sessionId: Int, payload: ByteArray, name: String): Boolean {
        val wire = encoder.encodeData(
            sessionId = sessionId,
            payload = payload,
            crcMode = crcMode
        )
        if (!writer(wire)) {
            failPeerDescription("$name write failed")
            return false
        }
        keepAlive.markTx(System.currentTimeMillis())
        listener.onProtocolEvent("PD TX session=$sessionId $name len=${payload.size} wireLen=${wire.size}")
        return true
    }

    private fun completePeerDescription(detail: String) {
        waitingForPeerConfirm = false
        phase = SapHandshakePhase.PROTOCOL_INIT
        listener.onPhaseChanged(phase, "Peer Description 2.1 complete: $detail; starting CAPEX")
        val query = SapCapabilityExchange.composeSyncQuery()
        val wire = encoder.encodeData(
            sessionId = SapProtocol.SESSION_ID_CAPEX,
            payload = query,
            crcMode = crcMode
        )
        if (!writer(wire)) {
            state = SapSessionState.ERROR
            listener.onProtocolError("CAPEX sync query write failed")
            return
        }
        keepAlive.markTx(System.currentTimeMillis())
        phase = SapHandshakePhase.CAPABILITY_EXCHANGE
        listener.onPhaseChanged(
            phase,
            "CAPEX sync query sent profile=${SapHandshake.PROFILE_ID} wireLen=${wire.size}"
        )
    }

    private fun failPeerDescription(message: String) {
        state = SapSessionState.ERROR
        listener.onProtocolError("Peer Description failed: $message")
    }

    private fun handleCapabilityExchange(frame: SapFrame) {
        phase = SapHandshakePhase.CAPABILITY_EXCHANGE
        val description = SapCapabilityExchange.describe(frame.payload)
        listener.onCapex(description)

        when (frame.payload.firstOrNull()?.toInt()?.and(0xff)) {
            SapCapabilityExchange.MESSAGE_TYPE_LEGACY_QUERY -> {
                val response = SapCapabilityExchange.composeLegacyResponse()
                val wire = encoder.encodeData(
                    sessionId = SapProtocol.SESSION_ID_CAPEX,
                    payload = response,
                    crcMode = crcMode
                )
                if (writer(wire)) {
                    keepAlive.markTx(System.currentTimeMillis())
                    listener.onProtocolEvent(
                        "legacy CAPEX response sent profile=${SapHandshake.PROFILE_ID} wireLen=${wire.size}"
                    )
                } else {
                    state = SapSessionState.ERROR
                    listener.onProtocolError("legacy CAPEX response write failed")
                }
            }

            SapCapabilityExchange.MESSAGE_TYPE_QUERY -> {
                val response = try {
                    SapCapabilityExchange.composeResponse(frame.payload)
                } catch (e: IllegalArgumentException) {
                    state = SapSessionState.ERROR
                    listener.onProtocolError("normal CAPEX query rejected: ${e.message}")
                    return
                }
                val wire = encoder.encodeData(
                    sessionId = SapProtocol.SESSION_ID_CAPEX,
                    payload = response,
                    crcMode = crcMode
                )
                if (writer(wire)) {
                    keepAlive.markTx(System.currentTimeMillis())
                    listener.onProtocolEvent(
                        "normal CAPEX response sent profile=${SapHandshake.PROFILE_ID} wireLen=${wire.size}"
                    )
                } else {
                    state = SapSessionState.ERROR
                    listener.onProtocolError("normal CAPEX response write failed")
                }
            }

            SapCapabilityExchange.MESSAGE_TYPE_RESPONSE ->
                listener.onProtocolEvent("normal CAPEX response received; waiting for service connection")

            SapCapabilityExchange.MESSAGE_TYPE_LEGACY_RESPONSE ->
                listener.onProtocolEvent("legacy CAPEX response received; waiting for service connection")

            else -> {
                listener.onProtocolEvent("CAPEX message observed without response: $description")
            }
        }
    }

    private fun handleServiceConnection(frame: SapFrame) {
        phase = SapHandshakePhase.SERVICE_CONNECTION
        val request = SapServiceConnection.parseRequest(frame.payload)
        if (request != null) {
            listener.onServiceConnectionRequest(request)
            if (request.profileId != SapHandshake.PROFILE_ID) {
                state = SapSessionState.ERROR
                listener.onProtocolError(
                    "service request ignored; unexpected profile=${request.profileId}"
                )
                return
            }

            val missingChannels = SapHandshake.REQUIRED_CHANNELS - request.channels.map { it.channelId }.toSet()
            if (missingChannels.isNotEmpty()) {
                listener.onProtocolEvent("service request missing channels=$missingChannels")
            }

            state = SapSessionState.CHANNEL_NEGOTIATING
            phase = SapHandshakePhase.CHANNEL_NEGOTIATION
            val responsePayload = SapServiceConnection.composeAcceptedResponse(request)
            val responseWire = encoder.encodeData(
                sessionId = SapProtocol.SESSION_ID_SERVICE_CONNECTION,
                payload = responsePayload,
                crcMode = crcMode
            )
            if (!writer(responseWire)) {
                state = SapSessionState.ERROR
                listener.onProtocolError("service connection response write failed")
                return
            }

            keepAlive.markTx(System.currentTimeMillis())
            val channels = mux.bind(request)
            listener.onChannelsOpen(channels)
            if (mux.isOpen(204)) {
                state = SapSessionState.READY
                phase = SapHandshakePhase.CHANNEL_204_OPEN
                Log.i(TAG_SAP, "SAP READY: channel 204 open")
                listener.onChannel204Open()
            } else {
                state = SapSessionState.ERROR
                listener.onProtocolError("service accepted but channel 204 is not open")
            }
            return
        }

        val response = SapServiceConnection.parseResponse(frame.payload)
        if (response != null) {
            listener.onProtocolEvent(
                "service connection response status=${response.statusCode} profile=${response.profileId} sessions=${response.sessionIds}"
            )
            return
        }

        listener.onProtocolEvent(
            "unknown service connection payload len=${frame.payload.size} hex=${frame.payload.toHexString(96)}"
        )
    }

    interface Listener {
        fun onPhaseChanged(phase: SapHandshakePhase, detail: String)
        fun onFrameRx(frame: SapFrame)
        fun onFrameTx(channel: Int, sessionId: Int, wireLength: Int)
        fun onCapex(description: String)
        fun onServiceConnectionRequest(request: ServiceConnectionRequest)
        fun onChannelsOpen(channels: List<SapChannel>)
        fun onChannel204Open()
        fun onChannelPayload(channel: Int, sessionId: Int, length: Int)
        fun onProtocolEvent(message: String)
        fun onProtocolError(message: String)
    }
}
