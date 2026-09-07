package io.github.teccheck.gear360app.transport.nativegear360.sap

data class SapChannel(
    val id: Int,
    val state: SapChannelState = SapChannelState.CLOSED,
    val sessionId: Int? = null
)

enum class SapChannelState {
    CLOSED,
    OPENING,
    OPEN
}
