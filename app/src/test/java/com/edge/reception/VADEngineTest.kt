package com.edge.reception

import com.edge.reception.ai.EnergyVADEngine
import org.junit.Assert.*
import org.junit.Test

class VADEngineTest {

    @Test
    fun testSilenceVsSpeechDetection() {
        val vad = EnergyVADEngine(speechThresholdRms = 400f, silenceHangoverMs = 1000L)

        // Generate silence buffer (amplitude 0)
        val silence = ShortArray(1600) { 0 }
        assertFalse("Pure silence should not trigger VAD", vad.isSpeechDetected(silence))

        // Generate voice-like tone buffer (amplitude 2000)
        val speech = ShortArray(1600) { i -> (Math.sin(i * 0.1) * 2000).toInt().toShort() }
        assertTrue("High energy signal must trigger VAD", vad.isSpeechDetected(speech))
    }
}
