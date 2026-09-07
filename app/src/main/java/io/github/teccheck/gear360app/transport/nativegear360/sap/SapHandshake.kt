package io.github.teccheck.gear360app.transport.nativegear360.sap

object SapHandshake {
    const val PROFILE_NAME = "G_360_APP"
    const val PROFILE_ID = "/system/DI_360_2D"
    val REQUIRED_CHANNELS = setOf(204, 222, 230)
}

enum class SapHandshakePhase {
    RFCOMM_CONNECTED,
    PROTOCOL_INIT,
    CAPABILITY_EXCHANGE,
    SERVICE_CONNECTION,
    CHANNEL_NEGOTIATION,
    CHANNEL_204_OPEN,
    GEAR360_SYNC,
    READY
}
