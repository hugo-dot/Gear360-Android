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

    override fun answerChallenge(clientChallenge: ByteArray): ByteArray {
        require(SapAccessoryAuthentication.isValidSecurityPacket(clientChallenge)) {
            "Malformed WSM client challenge"
        }
        ensureInitialized()

        val protocolVersion = runCatching { commonNative.getCurrentProtocolVersion() }
            .onFailure { Log.w(TAG, "Unable to query WSM protocol version; using legacy response size", it) }
            .getOrDefault(0)
        val responseSize = if (protocolVersion == PROTOCOL_VERSION_EXTENDED) {
            EXTENDED_SERVER_CHALLENGE_SIZE
        } else {
            LEGACY_SERVER_CHALLENGE_SIZE
        }
        val serverChallenge = ByteArray(responseSize)
        val result = clientNative.checkAndGenerateServerChallenge(
            handle,
            clientChallenge,
            serverChallenge
        )
        check(result <= 0) { "WSM challenge failed code=$result" }
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

        val result = clientNative.checkClientResponse(handle, clientResponse)
        check(result <= 0) { "WSM confirmation failed code=$result" }
        check(result != AUTHENTICATION_FAILED) { "WSM confirmation HMAC mismatch" }
        Log.i(TAG, "WSM authentication confirmed")
    }

    override fun close() {
        val activeHandle = handle
        handle = 0
        if (activeHandle <= 0) return
        runCatching { clientNative.destroy(activeHandle) }
            .onFailure { Log.w(TAG, "WSM destroy failed", it) }
    }

    private fun ensureInitialized() {
        if (handle > 0) return
        val created = clientNative.init(serverId, clientId)
        check(created > 0) { "WSM client initialization failed code=$created" }
        handle = created
        Log.i(TAG, "WSM client initialized for authenticated Bluetooth peers")
    }

    private companion object {
        const val PROTOCOL_VERSION_EXTENDED = 1
        const val LEGACY_SERVER_CHALLENGE_SIZE = 102
        const val EXTENDED_SERVER_CHALLENGE_SIZE = 200
        const val AUTHENTICATION_FAILED = -1
    }
}
