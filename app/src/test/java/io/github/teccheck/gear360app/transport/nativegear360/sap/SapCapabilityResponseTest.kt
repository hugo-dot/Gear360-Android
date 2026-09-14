package io.github.teccheck.gear360app.transport.nativegear360.sap

import org.junit.Assert.*
import org.junit.Test

class SapCapabilityResponseTest {
    @Test fun advertisesProviderAndApplicationVersionNotTransportVersion() {
        val query = SapCapabilityExchange.composeSyncQuery()
        val service = SapCapabilityResponse.parse(SapCapabilityExchange.composeResponse(query)).single()
        assertEquals(0, service.role)
        assertEquals(0x0100, service.profileVersion)
        assertEquals(SapHandshake.PROFILE_ID, service.profileId)
        assertEquals(1, service.componentId)
    }

    @Test fun matchingAndSyncResponsesCarryChecksumButAllDoesNot() {
        for (type in 1..3) {
            val response = SapCapabilityExchange.composeResponse(byteArrayOf(1, type.toByte()), registryChecksum = 0x12345678)
            if (type == 1) assertEquals(1, SapCrc.readUInt16(response, 2))
            else assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), response.copyOfRange(2, 6))
            assertEquals(1, SapCapabilityResponse.parse(response).size)
        }
    }

    @Test fun legacyResponseCanBeParsed() {
        val service = SapCapabilityResponse.parse(SapCapabilityExchange.composeLegacyResponse()).single()
        assertEquals(0, service.role)
        assertEquals(0x0100, service.profileVersion)
        assertEquals("DI_360_2DApp", service.friendlyName)
    }

    @Test fun truncatedResponsesAreRejectedAtEveryBoundary() {
        for (response in listOf(SapCapabilityExchange.composeLegacyResponse(),
            SapCapabilityExchange.composeResponse(SapCapabilityExchange.composeSyncQuery()))) {
            for (length in response.indices) {
                try {
                    SapCapabilityResponse.parse(response.copyOf(length))
                    fail("Accepted truncated CAPEX length=$length")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun invalidCountsAndTrailingDataAreRejected() {
        val valid = SapCapabilityExchange.composeLegacyResponse()
        for (invalid in listOf(valid + byteArrayOf(0), valid.copyOf().also { it[1] = 127 })) {
            try {
                SapCapabilityResponse.parse(invalid)
                fail("Accepted malformed CAPEX")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
