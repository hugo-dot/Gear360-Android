package io.github.teccheck.gear360app.transport.nativegear360.sap

import android.util.Log
import io.github.teccheck.gear360app.transport.nativegear360.toHexString

private const val TAG_SAP = "G360-SAP"
private const val TAG_CH204 = "G360-CH204"

class NativeSapSession(
    private val writer: (ByteArray) -> Boolean,
    private val listener: Listener,
    private val encoder: SapFrameEncoder = SapFrameEncoder()
) : Gear360SapSession {
    private val mux = SapChannelMux()
    private val keepAlive = SapKeepAlive()
    @Volatile private var state = SapSessionState.HANDSHAKING
    @Volatile private var phase = SapHandshakePhase.RFCOMM_CONNECTED
    @Volatile private var crcMode = SapTransportCrcMode.DISABLED

    fun onRfcommConnected() {
        phase = SapHandshakePhase.PROTOCOL_INIT
        listener.onPhaseChanged(phase, "RFCOMM connected; waiting for SAP frames")
    }

    fun handleFrame(frame: SapFrame): List<SapPayload> {
        keepAlive.markRx(System.currentTimeMillis())
        crcMode = frame.transportCrcMode
        listener.onFrameRx(frame)

        if (frame.frameType == SapFrameType.CONTROL) {
            listener.onProtocolEvent(
                "control frame session=${frame.sessionId} len=${frame.payload.size} payload=${frame.payload.toHexString(64)}"
            )
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
                state = SapSessionState.ERROR
                listener.onProtocolError(
                    "normal CAPEX query received; response format still needs HCI/JADX confirmation"
                )
            }

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
