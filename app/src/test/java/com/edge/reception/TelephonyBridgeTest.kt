package com.edge.reception

import com.edge.reception.telephony.LocalLoopAudioBridge
import com.edge.reception.telephony.TelephonyGatewayStatus
import com.edge.reception.telephony.VoipSipAudioBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyBridgeTest {

    @Test
    fun testTelephonyBridgeTypeSeparation() {
        val localBridge = LocalLoopAudioBridge()
        assertFalse(localBridge.isTwoWayTelephonySupported())
        assertTrue(localBridge.getBridgeType().contains("Local Audio Loop"))

        val carrierBridge = VoipSipAudioBridge("sip.telnyx.com:5060")
        assertTrue(carrierBridge.isTwoWayTelephonySupported())
        assertTrue(carrierBridge.getBridgeType().contains("Carrier Call-Forwarded SIP/PSTN Gateway"))
    }

    @Test
    fun testTelephonyGatewayStatusPrerequisitesValidation() {
        // Test unconfigured state
        val unconfigured = TelephonyGatewayStatus(
            configured = false,
            sipRegistered = false,
            inboundDid = null,
            sipRegistrarHost = null,
            missingPrerequisites = listOf("SIP_REGISTRAR_HOST", "SIP_TRUNK_CREDENTIALS", "PUBLIC_INBOUND_DID")
        )
        assertFalse(unconfigured.configured)
        assertFalse(unconfigured.sipRegistered)
        assertEquals(3, unconfigured.missingPrerequisites.size)
        assertTrue(unconfigured.missingPrerequisites.contains("PUBLIC_INBOUND_DID"))

        // Test configured state without live carrier registration
        val configured = TelephonyGatewayStatus(
            configured = true,
            sipRegistered = false,
            inboundDid = "+18005550199",
            sipRegistrarHost = "sip.telnyx.com",
            missingPrerequisites = emptyList()
        )
        assertTrue(configured.configured)
        assertFalse(configured.sipRegistered) // Truthful: registration not active without live carrier connect
        assertEquals("+18005550199", configured.inboundDid)
        assertEquals("sip.telnyx.com", configured.sipRegistrarHost)
    }
}
