package io.github.teccheck.gear360app.transport.nativegear360.sap

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

object SapCapabilityExchange {
    const val SERVICE_PROFILE = "/System/Reserved/ServiceCapabilityDiscovery"
    const val LEGACY_CHANNEL_ID = 255
    const val LOCAL_LEGACY_SESSION_ID = 1

    fun composeServiceRequest(): ByteArray = SapServiceConnection.composeRequest(
        acceptorId = 0xffff,
        initiatorId = 0xffff,
        profileId = SERVICE_PROFILE,
        channels = listOf(ServiceChannelRecord(LEGACY_CHANNEL_ID, LOCAL_LEGACY_SESSION_ID, QosRecord(1, 1, 3), 3))
    )
    const val MESSAGE_TYPE_QUERY = 1
    const val MESSAGE_TYPE_RESPONSE = 2
    const val MESSAGE_TYPE_LEGACY_QUERY = 5
    const val MESSAGE_TYPE_LEGACY_RESPONSE = 6
    private const val LEGACY_SERVICE_UUID = 1
    private const val LEGACY_COMPONENT_ID = 1
    // Application service profile version, independent of SAP transport version 2.1.
    private const val LEGACY_ASP_VERSION = 0x0100
    private const val LEGACY_ROLE_PROVIDER = 1
    private const val LEGACY_AGENT_COUNT = 1
    private const val LEGACY_QUERY_PERSISTENCE_MINUTES = 1440
    private const val NORMAL_ALE_UUID = 1
    private const val NORMAL_CONNECTION_TIMEOUT_SECONDS = 10

    const val QUERY_TYPE_ALL = 1
    const val QUERY_TYPE_MATCHING = 2
    const val QUERY_TYPE_SYNC = 3
    const val UNKNOWN_CHECKSUM = -1

    /**
     * Reproduces the initial query built by
     * SACapabilityManager.sendCapexSyncQueryMessage(). Samsung sends message type 1,
     * query type 3 followed by the profile count and persistent profile filters.
     * Unlike query types 1 and 2, the sync layout does not carry a checksum.
     */
    fun composeSyncQuery(
        profileIds: List<String> = listOf(SapHandshake.PROFILE_ID),
        @Suppress("UNUSED_PARAMETER") checksum: Int = UNKNOWN_CHECKSUM
    ): ByteArray {
        require(profileIds.size <= 0xff) { "too many CAPEX profiles: ${profileIds.size}" }
        val encodedProfiles = profileIds.map(SapProfileIdCodec::encode)
        val out = ByteArray(3 + encodedProfiles.sumOf(ByteArray::size))
        out[0] = MESSAGE_TYPE_QUERY.toByte()
        out[1] = QUERY_TYPE_SYNC.toByte()
        out[2] = profileIds.size.toByte()

        var offset = 3
        encodedProfiles.forEach { profile ->
            profile.copyInto(out, offset)
            offset += profile.size
        }
        return out
    }

    /**
     * Reproduces SACapexFrameUtils.composeCapabilityDiscoveryLegacyQueryMessage().
     * Normal profile identifiers are UTF-8 strings terminated by ';' on the wire.
     */
    fun composeLegacyQuery(profileId: String = SapHandshake.PROFILE_ID): ByteArray {
        val profile = SapProfileIdCodec.encode(profileId)
        val out = ByteArray(4 + profile.size)
        out[0] = MESSAGE_TYPE_LEGACY_QUERY.toByte()
        out[1] = 1 // normal query, one service record
        SapCrc.writeUInt16(LEGACY_QUERY_PERSISTENCE_MINUTES, out, 2)
        profile.copyInto(out, destinationOffset = 4)
        return out
    }

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

    /**
     * Wire layout from SACapexFrameUtils.composeCapabilityDiscoveryResponseMessage().
     * Matching and sync responses include the registry checksum; ALL omits it.
     */
    fun composeResponse(
        queryPayload: ByteArray,
        profileId: String = SapHandshake.PROFILE_ID,
        friendlyName: String = "DI_360_2DApp",
        registryChecksum: Int? = null
    ): ByteArray {
        require(queryPayload.size >= 2 && (queryPayload[0].toInt() and 0xff) == MESSAGE_TYPE_QUERY) {
            "not a normal CAPEX query"
        }

        val queryType = queryPayload[1].toInt() and 0xff
        require(queryType in QUERY_TYPE_ALL..QUERY_TYPE_SYNC) { "unknown CAPEX query type: $queryType" }

        val profile = SapProfileIdCodec.encode(profileId)
        val encodedFriendlyName = friendlyName.toByteArray(StandardCharsets.UTF_8)
        val friendlyBytes = encodedFriendlyName.copyOf(minOf(encodedFriendlyName.size, 30))
        val checksumBytes = if (queryType == QUERY_TYPE_ALL) 0 else 4
        val headerSize = 2 + checksumBytes + 2
        val out = ByteArray(
            headerSize +
                2 + friendlyBytes.size + 1 + 2 +
                2 + profile.size + 2 + 1 + 2
        )

        var offset = 0
        out[offset++] = MESSAGE_TYPE_RESPONSE.toByte()
        out[offset++] = queryType.toByte()
        if (checksumBytes != 0) {
            offset += 4
        }
        SapCrc.writeUInt16(1, out, offset)
        offset += 2

        SapCrc.writeUInt16(NORMAL_ALE_UUID, out, offset)
        offset += 2
        friendlyBytes.copyInto(out, offset)
        offset += friendlyBytes.size
        out[offset++] = ';'.code.toByte()
        SapCrc.writeUInt16(1, out, offset)
        offset += 2

        SapCrc.writeUInt16(LEGACY_COMPONENT_ID, out, offset)
        offset += 2
        profile.copyInto(out, offset)
        offset += profile.size
        SapCrc.writeUInt16(LEGACY_ASP_VERSION, out, offset)
        offset += 2
        out[offset++] = LEGACY_ROLE_PROVIDER.toByte()
        SapCrc.writeUInt16(NORMAL_CONNECTION_TIMEOUT_SECONDS, out, offset)
        if (checksumBytes != 0) {
            // Opaque registry change token, not the RFCOMM frame checksum. Never
            // echo the peer's cached checksum as if it described our registry.
            val checksum = registryChecksum ?: CRC32().apply {
                update(out, headerSize, out.size - headerSize)
            }.value.toInt()
            writeUInt32(checksum, out, 2)
        }
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
