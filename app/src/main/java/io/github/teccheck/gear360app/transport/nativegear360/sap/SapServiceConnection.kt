package io.github.teccheck.gear360app.transport.nativegear360.sap

object SapServiceConnection {
    const val MESSAGE_TYPE_CREATION_REQUEST = 1
    const val MESSAGE_TYPE_CREATION_RESPONSE = 2
    const val MESSAGE_TYPE_TERMINATION_REQUEST = 3
    const val MESSAGE_TYPE_TERMINATION_RESPONSE = 4
    const val STATUS_ACCEPTED = 0
    const val STATUS_REJECTED = 1

    fun parseRequest(payload: ByteArray): ServiceConnectionRequest? {
        if (payload.size < 8 || payload[0].toInt() != MESSAGE_TYPE_CREATION_REQUEST) {
            return null
        }

        var offset = 1
        val acceptorId = SapCrc.readUInt16(payload, offset)
        offset += 2
        val initiatorId = SapCrc.readUInt16(payload, offset)
        offset += 2

        val profile = SapProfileIdCodec.read(payload, offset) ?: return null
        offset = profile.nextOffset

        if (offset + 1 >= payload.size) return null
        val sessionCount = SapCrc.readUInt16(payload, offset)
        offset += 2
        if (sessionCount <= 0) return null

        val sessionIds = mutableListOf<Int>()
        repeat(sessionCount) {
            if (offset + 1 >= payload.size) return null
            sessionIds += SapCrc.readUInt16(payload, offset)
            offset += 2
        }

        val channelIds = mutableListOf<Int>()
        repeat(sessionCount) {
            if (offset + 1 >= payload.size) return null
            channelIds += SapCrc.readUInt16(payload, offset)
            offset += 2
        }

        val qosRecords = mutableListOf<QosRecord>()
        repeat(sessionCount) {
            if (offset + 2 >= payload.size) return null
            qosRecords += QosRecord(
                type = payload[offset].toInt() and 0xff,
                dataRate = payload[offset + 1].toInt() and 0xff,
                classType = payload[offset + 2].toInt() and 0xff
            )
            offset += 3
        }

        val payloadTypes = mutableListOf<Int>()
        repeat(sessionCount) {
            if (offset >= payload.size) return null
            payloadTypes += payload[offset].toInt() and 0xff
            offset += 1
        }

        val channels = channelIds.mapIndexed { index, channelId ->
            ServiceChannelRecord(
                channelId = channelId,
                sessionId = sessionIds[index],
                qos = qosRecords[index],
                payloadType = payloadTypes[index]
            )
        }

        return ServiceConnectionRequest(
            acceptorId = acceptorId,
            initiatorId = initiatorId,
            profileId = profile.value,
            sessionIds = sessionIds,
            channels = channels
        )
    }

    fun parseResponse(payload: ByteArray): ServiceConnectionResponse? {
        if (payload.size < 9 || payload[0].toInt() != MESSAGE_TYPE_CREATION_RESPONSE) {
            return null
        }

        var offset = 1
        val acceptorId = SapCrc.readUInt16(payload, offset)
        offset += 2
        val initiatorId = SapCrc.readUInt16(payload, offset)
        offset += 2
        val profile = SapProfileIdCodec.read(payload, offset) ?: return null
        offset = profile.nextOffset
        if (offset + 2 >= payload.size) return null

        val statusCode = payload[offset].toInt() and 0xff
        offset += 1
        val sessionCount = SapCrc.readUInt16(payload, offset)
        offset += 2
        val sessionIds = mutableListOf<Int>()
        repeat(sessionCount) {
            if (offset + 1 >= payload.size) return null
            sessionIds += SapCrc.readUInt16(payload, offset)
            offset += 2
        }

        return ServiceConnectionResponse(
            acceptorId = acceptorId,
            initiatorId = initiatorId,
            profileId = profile.value,
            statusCode = statusCode,
            sessionIds = sessionIds
        )
    }

    fun composeAcceptedResponse(request: ServiceConnectionRequest): ByteArray {
        val profileBytes = SapProfileIdCodec.encode(request.profileId)
        val responseSize = 1 + 2 + 2 + profileBytes.size + 1 + 2 + request.sessionIds.size * 2
        val out = ByteArray(responseSize)
        var offset = 0

        out[offset++] = MESSAGE_TYPE_CREATION_RESPONSE.toByte()
        SapCrc.writeUInt16(request.acceptorId, out, offset)
        offset += 2
        SapCrc.writeUInt16(request.initiatorId, out, offset)
        offset += 2
        profileBytes.copyInto(out, offset)
        offset += profileBytes.size
        out[offset++] = STATUS_ACCEPTED.toByte()
        SapCrc.writeUInt16(request.sessionIds.size, out, offset)
        offset += 2
        request.sessionIds.forEach { sessionId ->
            SapCrc.writeUInt16(sessionId, out, offset)
            offset += 2
        }

        return out
    }
}

data class ServiceConnectionRequest(
    val acceptorId: Int,
    val initiatorId: Int,
    val profileId: String,
    val sessionIds: List<Int>,
    val channels: List<ServiceChannelRecord>
)

data class ServiceConnectionResponse(
    val acceptorId: Int,
    val initiatorId: Int,
    val profileId: String,
    val statusCode: Int,
    val sessionIds: List<Int>
)

data class ServiceChannelRecord(
    val channelId: Int,
    val sessionId: Int,
    val qos: QosRecord,
    val payloadType: Int
)

data class QosRecord(
    val type: Int,
    val dataRate: Int,
    val classType: Int
)
