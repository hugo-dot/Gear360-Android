package io.github.teccheck.gear360app.transport.nativegear360

import android.content.Context
import android.util.Log
import io.github.teccheck.gear360app.transport.Gear360ControlTransport
import io.github.teccheck.gear360app.transport.nativegear360.sap.NativeSapSession
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapChannel
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapDecodeResult
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapFrame
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapFrameDecoder
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapHandshakePhase
import io.github.teccheck.gear360app.transport.nativegear360.sap.ServiceConnectionRequest
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import io.github.teccheck.gear360app.utils.DeviceDescription
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG_PHYSICAL = "G360-PHYSICAL"
private const val TAG_SDP = "G360-SDP"
private const val TAG_RFCOMM = "G360-RFCOMM"
private const val TAG_L2CAP = "G360-L2CAP"
private const val TAG_SAP = "G360-SAP"
private const val TAG_SAP_FRAME = "G360-SAP-FRAME"
private const val TAG_CH204 = "G360-CH204"

class NativeGear360Transport(
    context: Context,
    private val listener: Gear360ControlTransport.Listener
) : Gear360ControlTransport {
    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val decoder = SapFrameDecoder()
    private val linkLock = Any()
    private var clientLink: Gear360ClassicBluetoothLink? = null
    private var serverLink: Gear360ClassicBluetoothServerLink? = null
    private var sapSession: NativeSapSession? = null
    @Volatile private var activeSource: String? = null
    @Volatile private var activeWriter: ((ByteArray) -> Boolean)? = null

    override fun connect(device: DeviceDescription) {
        listener.onBackendSelected("NATIVE")

        if (!AndroidPermissionUtils.hasBluetoothConnectPermission(appContext)) {
            val reason = "Native Bluetooth diagnostics need BLUETOOTH_CONNECT"
            Log.w(TAG_PHYSICAL, reason)
            listener.onTransportUnavailable(reason)
            return
        }

        val adapter = AndroidPermissionUtils.bluetoothAdapter(appContext)
        if (adapter == null) {
            val reason = "Bluetooth adapter unavailable"
            Log.w(TAG_PHYSICAL, reason)
            listener.onTransportUnavailable(reason)
            return
        }

        val bluetoothDevice = try {
            adapter.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            listener.onTransportUnavailable("Invalid Bluetooth address: ${device.address}", e)
            return
        }

        worker.execute {
            decoder.reset()
            activeSource = null
            activeWriter = null
            sapSession = createSapSession(device)

            val summary = NativeBluetoothDiagnostics.describe(appContext, bluetoothDevice)
            Log.i(TAG_PHYSICAL, summary)
            Log.i(TAG_L2CAP, "L2CAP probing not used yet; RFCOMM service is the current physical lead")
            listener.onNativeBluetoothDiagnostics(summary)

            val uuids = Gear360SdpDiscovery(appContext).discover(bluetoothDevice)
            listener.onNativeBluetoothDiagnostics("SDP UUIDs=${uuids.toDisplayString()}")

            startRfcommServer()

            val controlUuid = Gear360SdpDiscovery.selectControlUuid(uuids)
                ?: Gear360SdpDiscovery.GEAR360_SAP_UUID_PRIMARY.takeIf {
                    AndroidPermissionUtils.isGear360DeviceName(device.name)
                }
            if (controlUuid == null) {
                Log.w(
                    TAG_SDP,
                    "No outbound RFCOMM/SAP candidate UUID for ${device.name}; waiting for inbound Gear360 connection"
                )
                listener.onNativeBluetoothDiagnostics(
                    "RFCOMM client skipped; server listener is waiting for camera"
                )
                return@execute
            }

            if (!uuids.contains(controlUuid)) {
                Log.w(
                    TAG_SDP,
                    "Using Gear360 observed UUID fallback=$controlUuid because SDP returned ${uuids.toDisplayString()}"
                )
            } else {
                Log.i(TAG_SDP, "Selected outbound RFCOMM UUID=$controlUuid")
            }

            val classicLink = Gear360ClassicBluetoothLink(
                appContext,
                object : Gear360ClassicBluetoothLink.Listener {
                    override fun onState(state: ClassicLinkState, detail: String) {
                        handleClassicState(LINK_CLIENT, state, detail)
                    }

                    override fun onRx(data: ByteArray) {
                        handleClassicRx(LINK_CLIENT, data)
                    }

                    override fun onClosed(reason: String, error: Throwable?) {
                        handleClassicClosed(LINK_CLIENT, reason, error)
                    }
                }
            )
            clientLink = classicLink
            classicLink.connect(bluetoothDevice, controlUuid)
        }
    }

    override fun disconnect(device: DeviceDescription?) {
        clientLink?.disconnect()
        clientLink = null
        serverLink?.disconnect()
        serverLink = null
        sapSession?.close()
        sapSession = null
        decoder.reset()
        synchronized(linkLock) {
            activeSource = null
            activeWriter = null
        }
    }

    override fun send(channelId: Int, data: ByteArray): Boolean {
        val session = sapSession
        if (session == null) {
            Log.w(TAG_SAP_FRAME, "Native send refused channel=$channelId len=${data.size}; SAP session missing")
            return false
        }

        val sent = session.send(channelId, data)
        if (!sent) {
            Log.w(
                TAG_SAP_FRAME,
                "Native send refused channel=$channelId len=${data.size}; channel is not negotiated"
            )
        }
        return sent
    }

    override fun release() {
        clientLink?.release()
        clientLink = null
        serverLink?.release()
        serverLink = null
        sapSession?.close()
        sapSession = null
        worker.shutdownNow()
    }

    private fun startRfcommServer() {
        val listenUuids = listOf(
            Gear360SdpDiscovery.GEAR360_SAP_UUID_PRIMARY,
            Gear360SdpDiscovery.GEAR360_SAP_UUID_SECONDARY
        )
        val server = Gear360ClassicBluetoothServerLink(
            appContext,
            object : Gear360ClassicBluetoothServerLink.Listener {
                override fun onState(state: ClassicLinkState, detail: String) {
                    handleClassicState(LINK_SERVER, state, detail)
                }

                override fun onRx(data: ByteArray) {
                    handleClassicRx(LINK_SERVER, data)
                }

                override fun onClosed(reason: String, error: Throwable?) {
                    handleClassicClosed(LINK_SERVER, reason, error)
                }
            }
        )
        serverLink = server
        server.listen(listenUuids)
    }

    private fun createSapSession(device: DeviceDescription): NativeSapSession {
        return NativeSapSession(
            writer = { bytes -> activeWriter?.invoke(bytes) == true },
            listener = object : NativeSapSession.Listener {
                override fun onPhaseChanged(phase: SapHandshakePhase, detail: String) {
                    Log.i(TAG_SAP, "phase=$phase $detail")
                    listener.onNativeBluetoothDiagnostics("SAP $phase $detail")
                }

                override fun onFrameRx(frame: SapFrame) {
                    Log.i(
                        TAG_SAP_FRAME,
                        "RX session=${frame.sessionId} type=${frame.frameType} crc=${frame.transportCrcMode} len=${frame.payload.size} HEX=${frame.payload.toHexString(96)}"
                    )
                }

                override fun onFrameTx(channel: Int, sessionId: Int, wireLength: Int) {
                    Log.i(TAG_SAP_FRAME, "TX channel=$channel session=$sessionId wireLen=$wireLength")
                }

                override fun onCapex(description: String) {
                    Log.i(TAG_SAP, description)
                    listener.onNativeBluetoothDiagnostics(description)
                }

                override fun onServiceConnectionRequest(request: ServiceConnectionRequest) {
                    val channels = request.channels.joinToString(",") {
                        "${it.channelId}->${it.sessionId}"
                    }
                    Log.i(
                        TAG_SAP,
                        "service request profile=${request.profileId} acceptor=${request.acceptorId} initiator=${request.initiatorId} channels=$channels"
                    )
                    listener.onSapPeerFound("Gear 360 native", device.address, request.profileId)
                    listener.onSapConnectionRequested("Gear 360 native", device.address, request.profileId)
                }

                override fun onChannelsOpen(channels: List<SapChannel>) {
                    val summary = channels.joinToString(",") { "${it.id}->${it.sessionId}:${it.state}" }
                    Log.i(TAG_SAP, "channels open $summary")
                    listener.onNativeBluetoothDiagnostics("SAP channels $summary")
                }

                override fun onChannel204Open() {
                    Log.i(TAG_CH204, "CHANNEL 204 OPEN")
                    listener.onSapSocketConnected("Gear 360 native", device.address, "channel 204")
                }

                override fun onChannelPayload(channel: Int, sessionId: Int, length: Int) {
                    Log.i(TAG_SAP_FRAME, "RX payload channel=$channel session=$sessionId len=$length")
                }

                override fun onProtocolEvent(message: String) {
                    Log.i(TAG_SAP, message)
                    listener.onNativeBluetoothDiagnostics(message)
                }

                override fun onProtocolError(message: String) {
                    Log.e(TAG_SAP, message)
                    listener.onSapConnectionFailed(NATIVE_SAP_NEGOTIATION_FAILED, message)
                }
            }
        )
    }

    private fun handleClassicState(source: String, state: ClassicLinkState, detail: String) {
        Log.i(TAG_RFCOMM, "$source $state $detail")
        listener.onPhysicalTransportState(state.name)
        listener.onNativeBluetoothDiagnostics("$source $state $detail")
        when (state) {
            ClassicLinkState.CLASSIC_LISTENING -> Unit
            ClassicLinkState.CLASSIC_CONNECTING -> Unit
            ClassicLinkState.CLASSIC_CONNECTED -> {
                activateWriter(source)
                sapSession?.onRfcommConnected()
            }
            ClassicLinkState.SAP_NEGOTIATING -> {
                listener.onSapConnectionRequested("Gear 360 native", source, null)
                Log.i(TAG_SAP, "SAP negotiation pending on $source; channel 204 remains closed")
            }
        }
    }

    private fun handleClassicRx(source: String, data: ByteArray) {
        Log.i(TAG_SAP_FRAME, "$source RX_RAW len=${data.size} HEX=${data.toHexString()}")
        listener.onNativeBluetoothDiagnostics(
            "$source RFCOMM RX len=${data.size} HEX=${data.toHexString(32)}"
        )
        when (val result = decoder.append(data)) {
            SapDecodeResult.NeedMoreData -> {
                Log.d(TAG_SAP_FRAME, "Need more SAP bytes")
            }

            is SapDecodeResult.UnknownFraming -> {
                Log.w(
                    TAG_SAP_FRAME,
                    "Unknown SAP framing reason=${result.reason} buffered=${result.bufferedBytes} sample=${result.sampleHex}"
                )
                listener.onNativeBluetoothDiagnostics(
                    "Unknown SAP framing reason=${result.reason} sample=${result.sampleHex}"
                )
            }

            is SapDecodeResult.Frames -> {
                val session = sapSession
                if (session == null) {
                    Log.e(TAG_SAP, "decoded SAP frames but session is missing")
                    return
                }
                for (frame in result.frames) {
                    val payloads = session.handleFrame(frame)
                    for (payload in payloads) {
                        listener.onReceive(payload.channel, payload.payload)
                    }
                }
            }
        }
    }

    private fun handleClassicClosed(source: String, reason: String, error: Throwable?) {
        Log.w(TAG_RFCOMM, "$source CLOSED reason=$reason", error)
        val active = activeSource
        val waitingForOtherSide = active == null &&
            ((source == LINK_CLIENT && serverLink?.isRunning() == true) || source == LINK_SERVER)
        if (waitingForOtherSide) {
            listener.onNativeBluetoothDiagnostics("$source RFCOMM closed: $reason")
            return
        }

        if (active != null && active != source) {
            Log.i(TAG_RFCOMM, "ignoring close from inactive RFCOMM source=$source active=$active")
            return
        }

        listener.onPhysicalTransportState("DISCONNECTED")
        listener.onSapConnectionFailed(
            NATIVE_SAP_NOT_READY,
            "Native RFCOMM closed before SAP/channel 204: $reason"
        )
    }

    private fun activateWriter(source: String) {
        synchronized(linkLock) {
            if (activeSource != null) {
                Log.i(TAG_RFCOMM, "active RFCOMM source already selected=$activeSource; ignoring $source")
                return
            }

            activeSource = source
            activeWriter = when (source) {
                LINK_SERVER -> { bytes -> serverLink?.write(bytes) == true }
                else -> { bytes -> clientLink?.write(bytes) == true }
            }
            Log.i(TAG_RFCOMM, "active RFCOMM source=$source")

            if (source == LINK_SERVER) {
                clientLink?.disconnect()
            } else {
                serverLink?.disconnect()
            }
        }
    }

    companion object {
        private const val LINK_CLIENT = "RFCOMM-CLIENT"
        private const val LINK_SERVER = "RFCOMM-SERVER"
        private const val NATIVE_SAP_NOT_READY = -360204
        private const val NATIVE_SAP_NEGOTIATION_FAILED = -360205
    }
}
