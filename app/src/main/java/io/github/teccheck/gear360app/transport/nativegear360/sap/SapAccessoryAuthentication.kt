package io.github.teccheck.gear360app.transport.nativegear360.sap

/** Samsung Accessory device packet carrying a WSM authentication packet. */
data class SapAccessoryAuthentication(
    val messageType: Int,
    val authenticationType: Int,
    val securityPacket: ByteArray
) {
    init {
        require(messageType in MESSAGE_TYPES) { "Unsupported authentication message type: $messageType" }
        require(authenticationType in 0..0xff) { "Authentication type out of range" }
        validateSecurityPacket(securityPacket)
    }

    fun compose(): ByteArray = byteArrayOf(messageType.toByte(), authenticationType.toByte()) + securityPacket

    companion object {
        const val ACCESSORY_AUTHENTICATE_REQUEST = 0x10
        const val ACCESSORY_AUTHENTICATE_RESPONSE = 0x11
        const val ACCESSORY_AUTHENTICATE_CONFIRM = 0x12
        const val ACCESSORY_AUTHENTICATE_INIT = 0x20

        private val MESSAGE_TYPES = setOf(
            ACCESSORY_AUTHENTICATE_REQUEST,
            ACCESSORY_AUTHENTICATE_RESPONSE,
            ACCESSORY_AUTHENTICATE_CONFIRM,
            ACCESSORY_AUTHENTICATE_INIT
        )

        fun parse(payload: ByteArray): SapAccessoryAuthentication? {
            if (payload.size < 5) return null
            val messageType = payload[0].toInt() and 0xff
            if (messageType !in MESSAGE_TYPES) return null

            val securityPacket = payload.copyOfRange(2, payload.size)
            if (!isValidSecurityPacket(securityPacket)) return null
            return SapAccessoryAuthentication(
                messageType = messageType,
                authenticationType = payload[1].toInt() and 0xff,
                securityPacket = securityPacket
            )
        }

        fun isValidSecurityPacket(packet: ByteArray): Boolean {
            if (packet.size < 3 || packet.size > 0xff) return false
            return (packet[2].toInt() and 0xff) == packet.size
        }

        private fun validateSecurityPacket(packet: ByteArray) {
            require(isValidSecurityPacket(packet)) {
                "Invalid WSM packet length: header=${packet.getOrNull(2)?.toInt()?.and(0xff)} actual=${packet.size}"
            }
        }
    }
}
