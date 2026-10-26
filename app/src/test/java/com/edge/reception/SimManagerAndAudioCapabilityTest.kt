package com.edge.reception

import com.edge.reception.telephony.CellularAudioAuditResult
import com.edge.reception.telephony.SimStatusResult
import com.edge.reception.telephony.SubscriptionDetail
import org.junit.Assert.*
import org.junit.Test

class SimManagerAndAudioCapabilityTest {

    @Test
    fun testSimStatusResultStructure() {
        val sub1 = SubscriptionDetail(
            subId = 2,
            slotIndex = 0,
            carrierName = "airtel",
            displayName = "SIM 1",
            countryIso = "in",
            rawPhoneNumber = null,
            isDefaultData = true,
            isDefaultVoice = true
        )
        val sub2 = SubscriptionDetail(
            subId = 1,
            slotIndex = 1,
            carrierName = "BSNL Mobile",
            displayName = "SIM 2",
            countryIso = "in",
            rawPhoneNumber = null,
            isDefaultData = false,
            isDefaultVoice = false
        )

        val result = SimStatusResult(
            totalCount = 2,
            subscriptions = listOf(sub1, sub2),
            isNumberExposed = false,
            primaryNumber = "+919876543210",
            requiresManualConfirmation = false
        )

        assertEquals(2, result.totalCount)
        assertFalse(result.isNumberExposed)
        assertEquals("+919876543210", result.primaryNumber)
        assertFalse(result.requiresManualConfirmation)
        assertTrue(result.subscriptions[0].isDefaultVoice)
        assertEquals("airtel", result.subscriptions[0].carrierName)
    }

    @Test
    fun testCellularAudioAuditResultTruthfulness() {
        val audit = CellularAudioAuditResult(
            captureAudioOutputGranted = false,
            modifyPhoneStateGranted = false,
            isSystemPrivilegedApp = false,
            voiceCallSourceSupported = false,
            directCellularAudioStatus = "BLOCKED_ANDROID_PRIVILEGE",
            restrictionReason = "Stock Android 16 (API 36) restricts CAPTURE_AUDIO_OUTPUT to signature|privileged system applications.",
            supportedAlternative = "CARRIER_SUPPORTED_CALL_FORWARDING_TO_EDGE_GATEWAY",
            evidence = listOf(
                "android.permission.CAPTURE_AUDIO_OUTPUT granted: false",
                "android.permission.MODIFY_PHONE_STATE granted: false",
                "Application system partition status: isSystem=false, isPrivileged=false"
            )
        )

        assertEquals("BLOCKED_ANDROID_PRIVILEGE", audit.directCellularAudioStatus)
        assertEquals("CARRIER_SUPPORTED_CALL_FORWARDING_TO_EDGE_GATEWAY", audit.supportedAlternative)
        assertFalse(audit.captureAudioOutputGranted)
        assertFalse(audit.isSystemPrivilegedApp)
        assertEquals(3, audit.evidence.size)
        assertTrue(audit.restrictionReason.contains("Android 16"))
    }
}
