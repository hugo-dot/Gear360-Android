package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WsmProtocolTest {
    @Test fun smR210ChallengeSelectsLegacyAbiAnd102ByteResponse() {
        val challenge = ByteArray(70).apply { this[2] = 70; this[3] = 4 }
        val protocol = WsmProtocol.forChallenge(challenge)
        assertEquals(1, protocol.version)
        assertEquals(102, protocol.responseSize)
        assertEquals(35, protocol.confirmationSize)
    }

    @Test fun extendedChallengeSelectsVersion2() {
        val challenge = ByteArray(136).apply { this[2] = 136.toByte() }
        assertEquals(WsmProtocol.EXTENDED, WsmProtocol.forChallenge(challenge))
        assertEquals(200, WsmProtocol.EXTENDED.responseSize)
    }

    @Test fun malformedAndUnsupportedChallengesFailBeforeNativeCall() {
        assertThrows(IllegalArgumentException::class.java) { WsmProtocol.forChallenge(ByteArray(70)) }
        assertThrows(IllegalArgumentException::class.java) {
            WsmProtocol.forChallenge(ByteArray(102).apply { this[2] = 102 })
        }
    }

    @Test fun nativeZeroAndNegativeResultsAreFailures() {
        WsmProtocol.requireSuccess(1, "challenge")
        for (code in listOf(0, -1, -704)) {
            assertThrows(IllegalStateException::class.java) { WsmProtocol.requireSuccess(code, "challenge") }
        }
    }
}
