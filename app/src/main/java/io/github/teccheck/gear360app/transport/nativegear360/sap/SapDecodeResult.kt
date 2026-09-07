package io.github.teccheck.gear360app.transport.nativegear360.sap

sealed class SapDecodeResult {
    data object NeedMoreData : SapDecodeResult()
    data class Frames(val frames: List<SapFrame>) : SapDecodeResult()
    data class UnknownFraming(
        val bufferedBytes: Int,
        val sampleHex: String,
        val reason: String = "unknown framing"
    ) : SapDecodeResult()
}
