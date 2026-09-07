package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertEquals
import org.junit.Test

class SapCrcTest {
    @Test
    fun crcUsesSamsungObservedCrc16IbmVariant() {
        assertEquals(0xbb3d, SapCrc.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun uint16IsBigEndianOnTheWire() {
        val bytes = SapCrc.uint16(0x1234)

        assertEquals(0x12, bytes[0].toInt() and 0xff)
        assertEquals(0x34, bytes[1].toInt() and 0xff)
    }
}
