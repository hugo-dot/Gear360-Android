package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SapKeepAliveTest {
    @Test
    fun agesAreUnavailableUntilTrafficIsObserved() {
        val keepAlive = SapKeepAlive()

        assertNull(keepAlive.lastRxAgeMs(100))
        assertNull(keepAlive.lastTxAgeMs(100))
    }

    @Test
    fun agesUseLastObservedTraffic() {
        val keepAlive = SapKeepAlive()

        keepAlive.markRx(100)
        keepAlive.markTx(250)

        assertEquals(50L, keepAlive.lastRxAgeMs(150))
        assertEquals(25L, keepAlive.lastTxAgeMs(275))
    }
}
