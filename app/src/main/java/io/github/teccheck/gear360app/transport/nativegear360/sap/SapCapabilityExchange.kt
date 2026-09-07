package io.github.teccheck.gear360app.transport.nativegear360.sap

import java.nio.charset.StandardCharsets

object SapCapabilityExchange {
    const val MESSAGE_TYPE_QUERY = 1
    const val MESSAGE_TYPE_RESPONSE = 2
    const val MESSAGE_TYPE_LEGACY_QUERY = 5
    const val MESSAGE_TYPE_LEGACY_RESPONSE = 6
    private const val LEGACY_SERVICE_UUID = 1
    private const val LEGACY_COMPONENT_ID = 1
    private const val LEGACY_ASP_VERSION = 0x0201
    private const val LEGACY_ROLE_PROVIDER = 1
    private const val LEGACY_AGENT_COUNT = 1

    fun describe(payload: ByteArray): String {
        if (payload.isEmpty()) return "CAPEX empty payload"
        return when (payload[0].toInt() and 0xff) {
            MESSAGE_TYPE_QUERY -> "CAPEX query ${describeNormalQuery(payload)}"
            MESSAGE_TYPE_RESPONSE -> "CAPEX response len=${payload.size}"
            MESSAGE_TYPE_LEGACY_QUERY -> "legacy CAPEX query ${describeLegacyQuery(payload)}"
            MESSAGE_TYPE_LEGACY_RESPONSE -> "legacy CAPEX response len=${payload.size}"
            else -> "unknown CAPEX messageType=${payload[0].toInt() and 0xff} len=${payload.size}"
        }
    }

    fun composeLegacyResponse(
        profileId: String = SapHandshake.PROFILE_ID,
        friendlyName: String = "DI_360_2DApp"
    ): ByteArray {
        val profile = SapProfileIdCodec.encode(profileId)
        val friendly = friendlyName.toByteArray(StandardCharsets.UTF_8)
            .copyOfRange(0, minOf(friendlyName.toByteArray(StandardCharsets.UTF_8).size, 30))
        val out = ByteArray(
            1 + 4 +
                2 +
                friendly.size + 1 +
                2 +
                2 +
                profile.size +
                2 +
                1
        )

        var offset = 0
        out[offset++] = MESSAGE_TYPE_LEGACY_RESPONSE.toByte()
        writeUInt32(1, out, offset)
        offset += 4
        SapCrc.writeUInt16(LEGACY_SERVICE_UUID, out, offset)
        offset += 2
        friendly.copyInto(out, offset)
        offset += friendly.size
        out[offset++] = ';'.code.toByte()
        SapCrc.writeUInt16(LEGACY_AGENT_COUNT, out, offset)
        offset += 2
        SapCrc.writeUInt16(LEGACY_COMPONENT_ID, out, offset)
        offset += 2
        profile.copyInto(out, offset)
        offset += profile.size
        SapCrc.writeUInt16(LEGACY_ASP_VERSION, out, offset)
        offset += 2
        out[offset] = ((LEGACY_ROLE_PROVIDER shl 6) and 0xc0).toByte()
        return out
    }

    private fun describeNormalQuery(payload: ByteArray): String {
        if (payload.size < 2) return "truncated"
        val queryType = payload[1].toInt() and 0xff
        return "queryType=$queryType len=${payload.size}"
    }

    private fun describeLegacyQuery(payload: ByteArray): String {
        if (payload.size < 4) return "truncated"
        val packed = payload[1].toInt() and 0xff
        val recordCount = packed and 0x7f
        val queryType = (packed ushr 7) and 0x01
        val persistenceMs = SapCrc.readUInt16(payload, 2)
        return "queryType=$queryType records=$recordCount persistence=$persistenceMs len=${payload.size}"
    }

    private fun writeUInt32(value: Int, target: ByteArray, offset: Int) {
        target[offset] = ((value ushr 24) and 0xff).toByte()
        target[offset + 1] = ((value ushr 16) and 0xff).toByte()
        target[offset + 2] = ((value ushr 8) and 0xff).toByte()
        target[offset + 3] = (value and 0xff).toByte()
    }
}
