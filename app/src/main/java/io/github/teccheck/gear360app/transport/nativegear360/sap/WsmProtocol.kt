package io.github.teccheck.gear360app.transport.nativegear360.sap

/** Packet sizes and return convention verified against the Accessory 3.1.93 JNI ABI. */
enum class WsmProtocol(val version: Int, val challengeSize: Int, val responseSize: Int, val confirmationSize: Int) {
    LEGACY(1, 70, 102, 35),
    EXTENDED(2, 136, 200, 67);

    companion object {
        fun forChallenge(packet: ByteArray): WsmProtocol {
            require(SapAccessoryAuthentication.isValidSecurityPacket(packet)) { "Malformed WSM challenge" }
            return entries.firstOrNull { it.challengeSize == packet.size }
                ?: throw IllegalArgumentException("Unsupported WSM challenge length=${packet.size}")
        }

        fun forVersion(version: Int): WsmProtocol = entries.firstOrNull { it.version == version }
            ?: throw IllegalArgumentException("Unsupported WSM version=$version")

        fun requireSuccess(result: Int, operation: String) {
            check(result > 0) { "WSM $operation failed code=$result" }
        }
    }
}
