package io.github.teccheck.gear360app.transport.nativegear360.sap

import io.github.teccheck.gear360app.transport.nativegear360.toHexString

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
    private var legacyDescriptionAnswered = false
    private var negotiatedCrcMode = SapTransportCrcMode.DISABLED
    private var capexSessionId = SapProtocol.SESSION_ID_CAPEX
    private var capexServicePending = false
    private var gearServicePending: ServiceConnectionRequest? = null
    private var capexReady = false

    @Synchronized fun onRfcommConnected() {
        state = SapSessionState.HANDSHAKING
        mux.clear()
        gearServicePending = null
        capexReady = false
        authenticator?.close()
        authenticator = null
        crcMode = SapTransportCrcMode.DISABLED
        legacyDescriptionAnswered = false
        negotiatedCrcMode = SapTransportCrcMode.DISABLED
        capexServicePending = false
        capexSessionId = SapProtocol.SESSION_ID_CAPEX
        peerDescriptionSessionId = null
        waitingForPeerConfirm = false
        phase = SapHandshakePhase.PEER_DESCRIPTION
        listener.onPhaseChanged(
            phase,
            "RFCOMM connected; waiting for Gear360 peer-description message"
        )
    }

    @Synchronized fun handleFrame(frame: SapFrame): List<SapPayload> {
        if (state == SapSessionState.CLOSED || state == SapSessionState.ERROR) return emptyList()
        keepAlive.markRx(System.currentTimeMillis())
        crcMode = frame.transportCrcMode
        listener.onFrameRx(frame)

        if (frame.frameType == SapFrameType.DEVICE) {
            if (SapAccessoryAuthentication.parse(frame.payload) != null) {
                handleAuthentication(frame)
            } else {
                handleLegacyPeerDescription(frame)
            }
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
            capexSessionId -> {
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
                        listener.onProtocolEvent("RX 204 len=${payload.payload.size}")
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

    @Synchronized override fun send(channel: Int, payload: ByteArray): Boolean {
        if (state != SapSessionState.READY) return false
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
                listener.onProtocolEvent("TX 204 session=$sessionId len=${payload.size}")
            }
            listener.onFrameTx(channel, sessionId, encoded.size)
        } else {
            listener.onProtocolEvent("TX failed channel=$channel session=$sessionId")
        }
        return written
    }

    @Synchronized override fun close() {
        state = SapSessionState.CLOSED
        phase = SapHandshakePhase.RFCOMM_CONNECTED
        peerDescriptionSessionId = null
        waitingForPeerConfirm = false
        gearServicePending = null
        capexReady = false
        mux.clear()
        authenticator?.close()
        authenticator = null
    }

    @Synchronized fun onHandshakeTimeout(
        reason: String = "SAP negotiation timeout at $phase; channel 204 is not open"
    ) {
        if (state == SapSessionState.READY || state == SapSessionState.CLOSED || state == SapSessionState.ERROR) return
        state = SapSessionState.ERROR
        mux.clear()
        authenticator?.close()
        authenticator = null
        listener.onProtocolError(reason)
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
                    if (legacyDescriptionAnswered) completePeerDescription("legacy response and WSM authentication accepted")
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

    private fun handleLegacyPeerDescription(frame: SapFrame) {
        val peer = peerDescription.parse(frame.payload)
        if (peer == null || peer.messageType != SapPeerDescription.MESSAGE_REQUEST) {
            failPeerDescription("unexpected legacy device message type=${peerDescription.messageType(frame.payload)}")
            return
        }
        if (legacyDescriptionAnswered) {
            listener.onProtocolEvent("Duplicate legacy PD request ignored while authenticating")
            return
        }
        listener.onProtocolEvent("PD REQUEST version=0x%04X product=%s manufacturer=%s".format(
            peer.protocolVersion, peer.productId, peer.manufacturerId))
        val response = try {
            peerDescription.composeLegacyResponse(peer)
        } catch (error: IllegalArgumentException) {
            failPeerDescription(error.message ?: "invalid legacy description")
            return
        }
        if (!writer(encoder.encodeDevicePacket(response, crcMode))) {
            failPeerDescription("legacy PD RESPONSE write failed")
            return
        }
        legacyDescriptionAnswered = true
        negotiatedCrcMode = if (peer.connectionlessMode == 1) SapTransportCrcMode.ENABLED else SapTransportCrcMode.DISABLED
        keepAlive.markTx(System.currentTimeMillis())
        listener.onProtocolEvent("PD RESPONSE sent as device packet; waiting for WSM authentication")
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
        if (legacyDescriptionAnswered) crcMode = negotiatedCrcMode
        phase = SapHandshakePhase.PROTOCOL_INIT
        listener.onPhaseChanged(phase, "Peer Description 2.1 complete: $detail; starting CAPEX")
        if (legacyDescriptionAnswered) {
            capexServicePending = true
            val wire = encoder.encodeData(SapProtocol.SESSION_ID_SERVICE_CONNECTION,
                SapCapabilityExchange.composeServiceRequest(), crcMode = crcMode)
            if (!writer(wire)) {
                failPeerDescription("CAPEX service creation write failed")
                return
            }
            phase = SapHandshakePhase.CAPABILITY_EXCHANGE
            listener.onPhaseChanged(phase, "SAP 2.1: requested ServiceCapabilityDiscovery on channel 255 with CRC=$crcMode")
            return
        }
        val query = SapCapabilityExchange.composeSyncQuery()
        capexReady = true
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
        if (gearServicePending == null && !mux.isOpen(204)) phase = SapHandshakePhase.CAPABILITY_EXCHANGE
        val description = SapCapabilityExchange.describe(frame.payload)
        listener.onCapex(description)

        when (frame.payload.firstOrNull()?.toInt()?.and(0xff)) {
            SapCapabilityExchange.MESSAGE_TYPE_LEGACY_QUERY -> {
                val response = SapCapabilityExchange.composeLegacyResponse()
                val wire = encoder.encodeData(
                    sessionId = capexSessionId,
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
                    sessionId = capexSessionId,
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

            SapCapabilityExchange.MESSAGE_TYPE_RESPONSE,
            SapCapabilityExchange.MESSAGE_TYPE_LEGACY_RESPONSE -> handlePeerServices(frame.payload)

            else -> {
                listener.onProtocolEvent("CAPEX message observed without response: $description")
            }
        }
    }

    private fun handlePeerServices(payload: ByteArray) {
        if (!capexReady) {
            listener.onProtocolEvent("Ignoring CAPEX response before capability service establishment")
            return
        }
        val services = try {
            SapCapabilityResponse.parse(payload)
        } catch (error: IllegalArgumentException) {
            state = SapSessionState.ERROR
            listener.onProtocolError("Invalid CAPEX response: ${error.message}")
            return
        }
        services.forEach {
            listener.onProtocolEvent("CAPEX peer profile=${it.profileId} component=${it.componentId} role=${it.role} version=0x%04X".format(it.profileVersion))
        }
        if (gearServicePending != null || mux.isOpen(204)) return
        val peer = services.singleOrNull {
            it.profileId == SapHandshake.PROFILE_ID && it.role == 1 && it.componentId != 0xffff
        }
        if (peer == null) {
            listener.onProtocolEvent("CAPEX has no unique Gear360 consumer; awaiting matching profile response")
            return
        }
        // Official Gear360 Manager: onPeerFound -> establishConnection -> requestServiceConnection.
        // SAServiceDescriptionParser maps reliability=disable to 4 and priority to classType.
        val channels = listOf(
            ServiceChannelRecord(204, 2, QosRecord(4, 0, 2), 0),
            ServiceChannelRecord(222, 3, QosRecord(4, 0, 0), 0),
            ServiceChannelRecord(230, 4, QosRecord(4, 0, 0), 0)
        )
        val request = ServiceConnectionRequest(peer.componentId, 1, peer.profileId,
            channels.map { it.sessionId }, channels)
        gearServicePending = request
        phase = SapHandshakePhase.SERVICE_CONNECTION
        listener.onServiceConnectionRequest(request)
        listener.onPhaseChanged(phase, "Requesting Gear360 service component=${peer.componentId}")
        val wire = encoder.encodeData(SapProtocol.SESSION_ID_SERVICE_CONNECTION,
            SapServiceConnection.composeRequest(request.acceptorId, request.initiatorId,
                request.profileId, request.channels), crcMode = crcMode)
        if (!writer(wire)) {
            state = SapSessionState.ERROR
            listener.onProtocolError("Gear360 service request write failed")
        }
    }

    private fun handleServiceConnection(frame: SapFrame) {
        phase = SapHandshakePhase.SERVICE_CONNECTION
        val request = SapServiceConnection.parseRequest(frame.payload)
        if (request != null) {
            listener.onServiceConnectionRequest(request)
            if (!capexReady) {
                listener.onProtocolEvent("Ignoring service request before authenticated CAPEX establishment")
                return
            }
            if (gearServicePending != null || mux.isOpen(204)) {
                listener.onProtocolEvent("Ignoring competing incoming service request while our session is pending/open")
                return
            }
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
                listener.onProtocolEvent("SAP READY: channel 204 open")
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
            val pending = gearServicePending
            if (pending != null && response.profileId == pending.profileId) {
                if (response.acceptorId != pending.acceptorId || response.initiatorId != pending.initiatorId ||
                    response.sessionIds != pending.sessionIds || response.statusCode != SapServiceConnection.STATUS_ACCEPTED) {
                    state = SapSessionState.ERROR
                    listener.onProtocolError("Gear360 service rejected or response mismatched: $response")
                    return
                }
                gearServicePending = null
                listener.onChannelsOpen(mux.bind(pending))
                state = SapSessionState.READY
                phase = SapHandshakePhase.CHANNEL_204_OPEN
                listener.onPhaseChanged(phase, "Gear360 accepted SAP sessions=${response.sessionIds}")
                listener.onChannel204Open()
                return
            }
            if (capexServicePending && response.profileId == SapCapabilityExchange.SERVICE_PROFILE) {
                if (response.statusCode != SapServiceConnection.STATUS_ACCEPTED ||
                    response.acceptorId != 0xffff || response.initiatorId != 0xffff ||
                    response.sessionIds != listOf(SapCapabilityExchange.LOCAL_LEGACY_SESSION_ID)) {
                    failPeerDescription("CAPEX service rejected or mismatched: $response")
                    return
                }
                capexServicePending = false
                capexReady = true
                capexSessionId = response.sessionIds.single()
                val wire = encoder.encodeData(capexSessionId, SapCapabilityExchange.composeSyncQuery(), crcMode = crcMode)
                if (!writer(wire)) {
                    failPeerDescription("CAPEX sync query write failed")
                    return
                }
                listener.onProtocolEvent("CAPEX service accepted session=$capexSessionId; sync query sent")
            }
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
