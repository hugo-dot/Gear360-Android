package io.github.teccheck.gear360app.transport.nativegear360

import android.content.Context
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.util.Log
import io.github.teccheck.gear360app.transport.Gear360ControlTransport
import io.github.teccheck.gear360app.transport.nativegear360.sap.NativeSapSession
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapChannel
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapDecodeResult
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapFrame
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapFrameDecoder
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapHandshakePhase
import io.github.teccheck.gear360app.transport.nativegear360.sap.SapPeerIdentity
import io.github.teccheck.gear360app.transport.nativegear360.sap.ServiceConnectionRequest
import io.github.teccheck.gear360app.utils.AndroidPermissionUtils
import io.github.teccheck.gear360app.utils.DeviceDescription
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.UUID

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
    private val reconnectScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val decoder = SapFrameDecoder(detectPeerDescription = true)
    private val linkLock = Any()
    private var clientLink: Gear360ClassicBluetoothLink? = null
    private var serverLink: Gear360ClassicBluetoothServerLink? = null
    private var sapSession: NativeSapSession? = null
    @Volatile private var activeSource: String? = null
    @Volatile private var activeWriter: ((ByteArray) -> Boolean)? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectDevice: BluetoothDevice? = null
    @Volatile private var reconnectControlUuid: UUID? = null

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
            reconnectAttempt = 0
            sapSession = createSapSession(device)

            val summary = NativeBluetoothDiagnostics.describe(appContext, bluetoothDevice)
            Log.i(TAG_PHYSICAL, summary)
            Log.i(TAG_L2CAP, "L2CAP probing not used yet; RFCOMM service is the current physical lead")
            listener.onNativeBluetoothDiagnostics(summary)

            // The Samsung framework advertises both inbound UUIDs continuously. The camera may
            // call back while SDP is still being refreshed, so publish our listeners first.
            startRfcommServer()

            val uuids = Gear360SdpDiscovery(appContext).discover(bluetoothDevice)
            listener.onNativeBluetoothDiagnostics("SDP UUIDs=${uuids.toDisplayString()}")

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

            reconnectDevice = bluetoothDevice
            reconnectControlUuid = controlUuid

            if (!uuids.contains(controlUuid)) {
                Log.w(
                    TAG_SDP,
                    "Using Gear360 observed UUID fallback=$controlUuid because SDP returned ${uuids.toDisplayString()}"
                )
            } else {
                Log.i(TAG_SDP, "Selected outbound RFCOMM UUID=$controlUuid")
            }

            val classicLink = createClientLink()
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
        reconnectAttempt = MAX_RFCOMM_RECONNECT_ATTEMPTS
        reconnectDevice = null
        reconnectControlUuid = null
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
        reconnectAttempt = MAX_RFCOMM_RECONNECT_ATTEMPTS
        reconnectScheduler.shutdownNow()
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
        val localAddress = NativeBluetoothDiagnostics.localAdapterAddress(appContext)
        if (localAddress == null) {
            Log.e(TAG_SAP, "Local Bluetooth address unavailable; WSM authentication cannot start")
            listener.onNativeBluetoothDiagnostics(
                "WSM local Bluetooth address unavailable; authentication will report an explicit error"
            )
        } else {
            Log.i(TAG_SAP, "WSM peer identities available local=$localAddress remote=${device.address}")
        }
        return NativeSapSession(
            writer = { bytes -> activeWriter?.invoke(bytes) == true },
            securityServerId = device.address,
            securityClientId = localAddress,
            peerIdentity = SapPeerIdentity(
                productId = Build.MODEL.ifBlank { "Android" },
                manufacturerId = Build.MANUFACTURER.ifBlank { "Android" },
                friendlyName = Build.MODEL.ifBlank { "Android" }
            ),
            listener = object : NativeSapSession.Listener {
                override fun onPhaseChanged(phase: SapHandshakePhase, detail: String) {
                    if (phase == SapHandshakePhase.PROTOCOL_INIT) decoder.peerDescriptionEnabled = false
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
                if (activateWriter(source)) {
                    sapSession?.onRfcommConnected()
                }
            }
            ClassicLinkState.SAP_NEGOTIATING -> {
                listener.onSapConnectionRequested("Gear 360 native", source, null)
                Log.i(TAG_SAP, "SAP negotiation pending on $source; channel 204 remains closed")
            }
        }
    }

    @Synchronized
    private fun handleClassicRx(source: String, data: ByteArray) {
        if (source != activeSource) {
            Log.w(TAG_SAP_FRAME, "Ignoring bytes from inactive RFCOMM link=$source")
            return
        }
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
            if (source == LINK_CLIENT) {
                scheduleClientReconnect()
            }
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

    private fun createClientLink(): Gear360ClassicBluetoothLink {
        return Gear360ClassicBluetoothLink(
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
    }

    private fun scheduleClientReconnect() {
        val attempt = reconnectAttempt + 1
        if (attempt > MAX_RFCOMM_RECONNECT_ATTEMPTS || reconnectScheduler.isShutdown) {
            listener.onNativeBluetoothDiagnostics(
                "RFCOMM retry limit reached; select Connect to Android and press Connect again"
            )
            return
        }
        reconnectAttempt = attempt
        listener.onNativeBluetoothDiagnostics(
            "RFCOMM retry $attempt/$MAX_RFCOMM_RECONNECT_ATTEMPTS in ${RFCOMM_RECONNECT_DELAY_SECONDS}s"
        )
        reconnectScheduler.schedule({
            if (activeSource != null || serverLink?.isRunning() != true) return@schedule
            val selected = reconnectDevice ?: return@schedule
            val uuid = reconnectControlUuid ?: return@schedule
            Log.i(TAG_RFCOMM, "retrying outbound RFCOMM attempt=$attempt uuid=$uuid")
            val oldLink = clientLink
            oldLink?.release()
            val replacement = createClientLink()
            clientLink = replacement
            replacement.connect(selected, uuid)
        }, RFCOMM_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun activateWriter(source: String): Boolean {
        synchronized(linkLock) {
            val previous = activeSource
            if (previous == source) {
                Log.i(TAG_RFCOMM, "RFCOMM source already active=$source")
                return false
            }

            if (previous == LINK_SERVER && source == LINK_CLIENT) {
                Log.i(TAG_RFCOMM, "inbound RFCOMM is already authoritative; ignoring outbound client")
                clientLink?.disconnect()
                return false
            }

            if (previous == LINK_CLIENT && source == LINK_SERVER) {
                // Samsung PD client does the same hand-over: an inbound UUID connection
                // replaces the provisional outbound connection while WAITING_FOR_PD.
                Log.i(TAG_RFCOMM, "switching provisional outbound RFCOMM to inbound peer-description link")
                decoder.reset()
            }

            activeSource = source
            activeWriter = when (source) {
                LINK_SERVER -> { bytes -> serverLink?.write(bytes) == true }
                else -> { bytes -> clientLink?.write(bytes) == true }
            }
            Log.i(TAG_RFCOMM, "active RFCOMM source=$source")

            if (source == LINK_SERVER) {
                clientLink?.disconnect()
            }
            return true
        }
    }

    companion object {
        private const val LINK_CLIENT = "RFCOMM-CLIENT"
        private const val LINK_SERVER = "RFCOMM-SERVER"
        private const val NATIVE_SAP_NOT_READY = -360204
        private const val NATIVE_SAP_NEGOTIATION_FAILED = -360205
        private const val MAX_RFCOMM_RECONNECT_ATTEMPTS = 6
        private const val RFCOMM_RECONNECT_DELAY_SECONDS = 4L
    }
}
