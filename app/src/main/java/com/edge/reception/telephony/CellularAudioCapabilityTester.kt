package com.edge.reception.telephony

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat

data class CellularAudioAuditResult(
    val captureAudioOutputGranted: Boolean,
    val modifyPhoneStateGranted: Boolean,
    val isSystemPrivilegedApp: Boolean,
    val voiceCallSourceSupported: Boolean,
    val directCellularAudioStatus: String,
    val restrictionReason: String,
    val supportedAlternative: String,
    val evidence: List<String>
)

object CellularAudioCapabilityTester {

    fun auditDeviceCapability(context: Context): CellularAudioAuditResult {
        val evidence = mutableListOf<String>()

        // 1. Check CAPTURE_AUDIO_OUTPUT (protection level: signature|privileged|role)
        val captureAudioPermission = "android.permission.CAPTURE_AUDIO_OUTPUT"
        val hasCaptureAudio = ContextCompat.checkSelfPermission(
            context, captureAudioPermission
        ) == PackageManager.PERMISSION_GRANTED
        evidence.add("android.permission.CAPTURE_AUDIO_OUTPUT granted: $hasCaptureAudio")

        // 2. Check MODIFY_PHONE_STATE (protection level: signature|privileged)
        val modifyPhoneStatePermission = "android.permission.MODIFY_PHONE_STATE"
        val hasModifyPhoneState = ContextCompat.checkSelfPermission(
            context, modifyPhoneStatePermission
        ) == PackageManager.PERMISSION_GRANTED
        evidence.add("android.permission.MODIFY_PHONE_STATE granted: $hasModifyPhoneState")

        // 3. Check App System Partition Status
        val appInfo = context.applicationInfo
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        evidence.add("Application system partition status: isSystem=$isSystem (installed in ${appInfo.sourceDir})")

        // 4. Test AudioRecord with VOICE_CALL / VOICE_DOWNLINK
        var voiceCallSupported = false
        var testErrorReason = ""

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf > 0) {
                // AudioSource 4 = VOICE_CALL, AudioSource 3 = VOICE_DOWNLINK
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                voiceCallSupported = (record.state == AudioRecord.STATE_INITIALIZED)
                evidence.add("AudioRecord(VOICE_CALL) initialization state: ${record.state}")
                record.release()
            }
        } catch (e: SecurityException) {
            testErrorReason = "SecurityException: ${e.message}"
            evidence.add("AudioRecord(VOICE_CALL) rejected: $testErrorReason")
        } catch (e: Exception) {
            testErrorReason = "${e.javaClass.simpleName}: ${e.message}"
            evidence.add("AudioRecord(VOICE_CALL) error: $testErrorReason")
        }

        val isDirectSupported = hasCaptureAudio && hasModifyPhoneState && voiceCallSupported

        val status = if (isDirectSupported) {
            "PASS"
        } else {
            "BLOCKED_ANDROID_PRIVILEGE"
        }

        val reason = if (isDirectSupported) {
            "Device firmware and platform permissions allow direct cellular call audio capture and uplink injection."
        } else {
            "Stock Android 16 (API 36) restricts CAPTURE_AUDIO_OUTPUT and MODIFY_PHONE_STATE to signature|privileged system applications. The audio HAL routes cellular baseband directly between the radio modem and hardware speaker/mic, blocking ordinary user-space APKs from raw cellular audio access."
        }

        val alternative = "CARRIER_SUPPORTED_CALL_FORWARDING_TO_EDGE_GATEWAY"

        return CellularAudioAuditResult(
            captureAudioOutputGranted = hasCaptureAudio,
            modifyPhoneStateGranted = hasModifyPhoneState,
            isSystemPrivilegedApp = isSystem,
            voiceCallSourceSupported = voiceCallSupported,
            directCellularAudioStatus = status,
            restrictionReason = reason,
            supportedAlternative = alternative,
            evidence = evidence
        )
    }
}
