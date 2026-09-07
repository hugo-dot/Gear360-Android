package io.github.teccheck.gear360app.transport.nativegear360.sap

class SapKeepAlive {
    private var lastRxAtMs: Long = 0L
    private var lastTxAtMs: Long = 0L

    fun markRx(nowMs: Long) {
        lastRxAtMs = nowMs
    }

    fun markTx(nowMs: Long) {
        lastTxAtMs = nowMs
    }

    fun lastRxAgeMs(nowMs: Long): Long? {
        if (lastRxAtMs == 0L) return null
        return nowMs - lastRxAtMs
    }

    fun lastTxAgeMs(nowMs: Long): Long? {
        if (lastTxAtMs == 0L) return null
        return nowMs - lastTxAtMs
    }
}
