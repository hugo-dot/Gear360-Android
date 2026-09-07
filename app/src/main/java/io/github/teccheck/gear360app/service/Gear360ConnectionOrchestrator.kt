package io.github.teccheck.gear360app.service

import android.util.Log
import io.github.teccheck.gear360app.utils.DeviceDescription

private const val TAG = "G360-ORCH"
private const val PAIR_RETRY_GUARD_MS = 30_000L

class Gear360ConnectionOrchestrator(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var phase = Gear360ConnectionPhase.IDLE
    private var activeDevice: DeviceDescription? = null
    private var lastPairAttemptAt = 0L

    @Synchronized
    fun beginConnect(device: DeviceDescription): Boolean {
        if (!phase.canStartConnect()) {
            logW("connect ignored phase=$phase device=${device.name}")
            return false
        }

        activeDevice = device
        phase = Gear360ConnectionPhase.CLASSIC_CONNECTING
        logI("phase=$phase device=${device.name}")
        return true
    }

    @Synchronized
    fun beginPair(device: DeviceDescription): Boolean {
        val now = nowMs()
        if (!phase.canStartPair()) {
            logW("pair ignored phase=$phase device=${device.name}")
            return false
        }
        if (now - lastPairAttemptAt < PAIR_RETRY_GUARD_MS) {
            logW("pair ignored by retry guard device=${device.name}")
            return false
        }

        activeDevice = device
        lastPairAttemptAt = now
        phase = Gear360ConnectionPhase.PAIRING
        logI("phase=$phase device=${device.name}")
        return true
    }

    @Synchronized
    fun mark(newPhase: Gear360ConnectionPhase) {
        if (phase == newPhase) return
        phase = newPhase
        logI("phase=$phase device=${activeDevice?.name ?: "---"}")
    }

    @Synchronized
    fun reset() {
        phase = Gear360ConnectionPhase.IDLE
        activeDevice = null
        logI("phase=$phase")
    }

    @Synchronized
    fun error(reason: String) {
        phase = Gear360ConnectionPhase.ERROR
        logE("phase=$phase reason=$reason device=${activeDevice?.name ?: "---"}")
    }

    @Synchronized
    fun currentPhase(): Gear360ConnectionPhase = phase

    private fun logI(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun logW(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun logE(message: String) {
        try {
            Log.e(TAG, message)
        } catch (_: RuntimeException) {
        }
    }
}

enum class Gear360ConnectionPhase {
    IDLE,
    DISCOVERING,
    FOUND,
    PAIRING,
    CLASSIC_CONNECTING,
    CLASSIC_CONNECTED,
    SAP_HANDSHAKING,
    SAP_CONNECTED,
    CHANNEL_OPENING,
    PROTOCOL_SYNCING,
    READY,
    DISCONNECTING,
    ERROR;

    fun canStartConnect(): Boolean {
        return this == IDLE || this == ERROR || this == READY
    }

    fun canStartPair(): Boolean {
        return this == IDLE || this == ERROR
    }
}
