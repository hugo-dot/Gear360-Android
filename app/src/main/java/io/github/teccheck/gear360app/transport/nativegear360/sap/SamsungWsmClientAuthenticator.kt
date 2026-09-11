package io.github.teccheck.gear360app.transport.nativegear360.sap

import android.util.Log
import com.sec.android.WSM.ClientNative
import com.sec.android.WSM.CommonNative

private const val TAG = "G360-SAP-AUTH"

/**
 * Small compatibility wrapper around the WSM native ABI shipped with Accessory Service 3.1.93.
 * No Samsung framework Java code is embedded or called by this class.
 */
interface SapClientAuthenticator : AutoCloseable {
    fun answerChallenge(clientChallenge: ByteArray): ByteArray
    fun verifyConfirmation(clientResponse: ByteArray)
}

class SamsungWsmClientAuthenticator(
    private val serverId: String,
    private val clientId: String,
    private val clientNative: ClientNative = ClientNative(),
    private val commonNative: CommonNative = CommonNative()
) : SapClientAuthenticator {
    private var handle: Long = 0
    private var protocolVersion = 0

    override fun answerChallenge(clientChallenge: ByteArray): ByteArray {
        require(SapAccessoryAuthentication.isValidSecurityPacket(clientChallenge)) {
            "Malformed WSM client challenge"
        }
        val selected = WsmProtocol.forChallenge(clientChallenge)
        ensureInitialized(selected.version)
        val daemonReachable = runCatching { commonNative.isDaemonReachable() }
            .onFailure { Log.w(TAG, "Unable to query the Samsung WSM daemon", it) }
            .getOrNull()
        Log.i(
            TAG,
            "WSM challenge processing protocol=$protocolVersion daemonReachable=${daemonReachable ?: "unknown"} requestLen=${clientChallenge.size}"
        )
        val responseSize = selected.responseSize
        val serverChallenge = ByteArray(responseSize)
        val result = clientNative.checkAndGenerateServerChallenge(
            handle,
            clientChallenge,
            serverChallenge
        )
        Log.i(
            TAG,
            "WSM native challenge result=$result responseHeader=${serverChallenge.take(3).joinToString(" ") { "%02X".format(it) }}"
        )
        WsmProtocol.requireSuccess(result, "challenge")
        check(SapAccessoryAuthentication.isValidSecurityPacket(serverChallenge)) {
            "WSM generated invalid challenge length=${serverChallenge.getOrNull(2)?.toInt()?.and(0xff)} expected=$responseSize"
        }
        Log.i(TAG, "WSM challenge accepted protocol=$protocolVersion responseLen=${serverChallenge.size}")
        return serverChallenge
    }

    override fun verifyConfirmation(clientResponse: ByteArray) {
        require(SapAccessoryAuthentication.isValidSecurityPacket(clientResponse)) {
            "Malformed WSM confirmation"
        }
        check(handle > 0) { "WSM confirmation received before challenge" }
        require(clientResponse.size == WsmProtocol.forVersion(protocolVersion).confirmationSize) {
            "Unexpected WSM confirmation length=${clientResponse.size} version=$protocolVersion"
        }

        val result = clientNative.checkClientResponse(handle, clientResponse)
        WsmProtocol.requireSuccess(result, "confirmation")
        Log.i(TAG, "WSM authentication confirmed")
    }

    override fun close() {
        val activeHandle = handle
        handle = 0
        if (activeHandle <= 0) return
        runCatching { clientNative.destroy(activeHandle) }
            .onFailure { Log.w(TAG, "WSM destroy failed", it) }
    }

    private fun ensureInitialized(version: Int) {
        if (handle > 0) {
            check(protocolVersion == version) { "WSM protocol changed during authentication" }
            return
        }
        check(commonNative.setProtocolVersion(version) == version) {
            "WSM protocol version $version could not be selected"
        }
        protocolVersion = version
        val created = clientNative.init(serverId, clientId)
        check(created > 0) { "WSM client initialization failed code=$created" }
        handle = created
        Log.i(TAG, "WSM client initialized for authenticated Bluetooth peers")
    }

}
