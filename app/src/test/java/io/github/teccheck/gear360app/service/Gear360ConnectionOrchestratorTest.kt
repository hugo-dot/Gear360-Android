package io.github.teccheck.gear360app.service

import io.github.teccheck.gear360app.utils.DeviceDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gear360ConnectionOrchestratorTest {
    private val camera = DeviceDescription(
        address = "02:00:00:00:00:01",
        name = "Gear 360 (TEST)",
        type = DeviceType.R210
    )

    @Test
    fun rejectsSecondConnectWhileClassicConnectionIsActive() {
        val orchestrator = Gear360ConnectionOrchestrator()

        assertTrue(orchestrator.beginConnect(camera))
        assertFalse(orchestrator.beginConnect(camera))
        assertEquals(Gear360ConnectionPhase.CLASSIC_CONNECTING, orchestrator.currentPhase())
    }

    @Test
    fun resetAllowsNewConnectionAttempt() {
        val orchestrator = Gear360ConnectionOrchestrator()

        assertTrue(orchestrator.beginConnect(camera))
        orchestrator.reset()

        assertTrue(orchestrator.beginConnect(camera))
    }

    @Test
    fun pairingCannotRunDuringConnection() {
        val orchestrator = Gear360ConnectionOrchestrator()

        assertTrue(orchestrator.beginConnect(camera))

        assertFalse(orchestrator.beginPair(camera))
    }
}
