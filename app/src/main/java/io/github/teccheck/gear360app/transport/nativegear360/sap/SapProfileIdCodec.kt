package io.github.teccheck.gear360app.transport.nativegear360.sap

import java.nio.charset.StandardCharsets

internal object SapProfileIdCodec {
    private const val FIXED_PROFILE_LENGTH = 17
    private const val MAX_VARIABLE_PROFILE_LENGTH = 64

    fun encode(profileId: String): ByteArray {
        val bytes = profileId.toByteArray(StandardCharsets.UTF_8)
        return if (bytes.firstOrNull()?.toInt() == '='.code) {
            require(bytes.size >= FIXED_PROFILE_LENGTH) {
                "fixed SAP profile id must contain at least $FIXED_PROFILE_LENGTH bytes: $profileId"
            }
            bytes.copyOfRange(0, FIXED_PROFILE_LENGTH)
        } else {
            require(bytes.size <= MAX_VARIABLE_PROFILE_LENGTH) {
                "variable SAP profile id exceeds $MAX_VARIABLE_PROFILE_LENGTH bytes: $profileId"
            }
            bytes + ';'.code.toByte()
        }
    }

    fun read(payload: ByteArray, startOffset: Int): ReadResult? {
        if (startOffset >= payload.size) return null

        return if (payload[startOffset].toInt() == '='.code) {
            val end = startOffset + FIXED_PROFILE_LENGTH
            if (end > payload.size) return null
            ReadResult(
                value = payload.copyOfRange(startOffset, end).toString(StandardCharsets.UTF_8),
                nextOffset = end
            )
        } else {
            var offset = startOffset
            while (offset < payload.size && offset - startOffset < MAX_VARIABLE_PROFILE_LENGTH) {
                if (payload[offset].toInt() == ';'.code) {
                    return ReadResult(
                        value = payload.copyOfRange(startOffset, offset).toString(StandardCharsets.UTF_8),
                        nextOffset = offset + 1
                    )
                }
                offset += 1
            }
            null
        }
    }

    data class ReadResult(
        val value: String,
        val nextOffset: Int
    )
}
