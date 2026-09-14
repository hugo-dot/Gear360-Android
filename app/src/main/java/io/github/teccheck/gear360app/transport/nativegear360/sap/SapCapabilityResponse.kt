package io.github.teccheck.gear360app.transport.nativegear360.sap

import java.nio.ByteBuffer

data class SapPeerService(
    val agentId: Int,
    val componentId: Int,
    val profileId: String,
    val profileVersion: Int,
    val role: Int,
    val friendlyName: String
)

/** CAPEX records are untrusted until their complete, bounded layout is validated. */
object SapCapabilityResponse {
    fun parse(payload: ByteArray): List<SapPeerService> {
        val input = ByteBuffer.wrap(payload)
        fun u8(): Int {
            require(input.remaining() >= 1) { "Truncated CAPEX byte" }
            return input.get().toInt() and 0xff
        }
        fun u16(): Int {
            require(input.remaining() >= 2) { "Truncated CAPEX word" }
            return input.short.toInt() and 0xffff
        }
        fun text(maxLength: Int): String {
            val start = input.position()
            var size = 0
            while (input.hasRemaining() && size <= maxLength) {
                if (u8() == ';'.code) return payload.copyOfRange(start, input.position() - 1).decodeToString()
                size++
            }
            throw IllegalArgumentException("Unterminated or oversized CAPEX name")
        }
        fun profile(): String {
            val value = SapProfileIdCodec.read(payload, input.position())
                ?: throw IllegalArgumentException("Invalid CAPEX profile")
            require(value.nextOffset - input.position() <= 65) { "Oversized CAPEX profile" }
            input.position(value.nextOffset)
            return value.value
        }

        val legacy = when (val type = u8()) {
            SapCapabilityExchange.MESSAGE_TYPE_RESPONSE -> false
            SapCapabilityExchange.MESSAGE_TYPE_LEGACY_RESPONSE -> true
            else -> throw IllegalArgumentException("Not a CAPEX response: $type")
        }
        val agentCount = if (legacy) {
            require(input.remaining() >= 4) { "Truncated legacy CAPEX count" }
            input.int
        } else {
            val queryType = u8()
            require(queryType in 1..3) { "Invalid CAPEX query type=$queryType" }
            if (queryType != SapCapabilityExchange.QUERY_TYPE_ALL) {
                require(input.remaining() >= 4) { "Truncated CAPEX checksum" }
                input.int
            }
            u16()
        }
        require(agentCount in 0..256) { "Invalid CAPEX agent count=$agentCount" }
        val services = mutableListOf<SapPeerService>()
        repeat(agentCount) {
            val agentId = u16()
            val name = text(30)
            val count = u16()
            require(count in 0..256 && services.size + count <= 256) { "Too many CAPEX services" }
            repeat(count) {
                val componentId = u16()
                val profileId = profile()
                val version = u16()
                val roleFlags = u8()
                val role = if (legacy) (roleFlags ushr 6) and 3 else roleFlags and 3
                if (!legacy) u16() // Service connection timeout, not part of its identity.
                services += SapPeerService(agentId, componentId, profileId, version, role, name)
            }
        }
        require(!input.hasRemaining()) { "Trailing CAPEX bytes=${input.remaining()}" }
        return services
    }
}
