package io.github.teccheck.gear360app.transport.nativegear360.sap

enum class SapFrameType {
    DATA,
    CONTROL,
    DEVICE,
}

enum class SapTransportCrcMode {
    DISABLED,
    ENABLED,
}

data class SapFrame(
    val channel: Int,
    val payload: ByteArray,
    val raw: ByteArray,
    val sessionId: Int = channel,
    val frameType: SapFrameType = SapFrameType.DATA,
    val fragmentation: Int = 0,
    val sequenceNumber: Int? = null,
    val transportCrcMode: SapTransportCrcMode = SapTransportCrcMode.DISABLED
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SapFrame
        return channel == other.channel &&
            sessionId == other.sessionId &&
            frameType == other.frameType &&
            fragmentation == other.fragmentation &&
            sequenceNumber == other.sequenceNumber &&
            transportCrcMode == other.transportCrcMode &&
            payload.contentEquals(other.payload) &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = channel
        result = 31 * result + sessionId
        result = 31 * result + frameType.hashCode()
        result = 31 * result + fragmentation
        result = 31 * result + (sequenceNumber ?: 0)
        result = 31 * result + transportCrcMode.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}
