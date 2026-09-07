package io.github.teccheck.gear360app.transport.nativegear360.sap

interface Gear360SapSession {
    fun state(): SapSessionState
    fun openChannel(channel: Int): Boolean
    fun send(channel: Int, payload: ByteArray): Boolean
    fun close()
}

enum class SapSessionState {
    IDLE,
    HANDSHAKING,
    CHANNEL_NEGOTIATING,
    READY,
    CLOSED,
    ERROR
}
