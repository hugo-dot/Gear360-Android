package io.github.teccheck.gear360app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gear360StatusTest {
    @Test
    fun recordingIsDetectedFromRecordOrCaptureState() {
        assertTrue(Gear360Status(recordState = CaptureState.RECORDING).isRecording())
        assertTrue(Gear360Status(captureState = CaptureState.RECORDING).isRecording())
        assertFalse(Gear360Status(captureState = CaptureState.NONE).isRecording())
    }

    @Test
    fun recordableTimeIsFormatted() {
        assertEquals("01:02:03", Gear360Status(recordableTime = 3723).recordableTimeString())
    }

    @Test
    fun storagePercentageUsesKnownValues() {
        assertEquals(25, Gear360Status(totalStorage = 400, usedStorage = 100).usedStoragePercentage())
    }
}
