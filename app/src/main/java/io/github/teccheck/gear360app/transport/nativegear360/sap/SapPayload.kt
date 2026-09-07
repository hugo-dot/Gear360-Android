package io.github.teccheck.gear360app.transport.nativegear360.sap

data class SapPayload(
    val channel: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SapPayload
        return channel == other.channel && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        return 31 * channel + payload.contentHashCode()
    }
}
