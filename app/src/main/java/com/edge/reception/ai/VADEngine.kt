package com.edge.reception.ai

interface VADEngine {
    fun isSpeechDetected(audioBuffer: ShortArray): Boolean
    fun getSilenceDurationMs(): Long
    fun reset()
}

class EnergyVADEngine(
    private val speechThresholdRms: Float = 500f,
    private val silenceHangoverMs: Long = 1200L
) : VADEngine {

    private var lastSpeechTimestamp: Long = 0L

    override fun isSpeechDetected(audioBuffer: ShortArray): Boolean {
        if (audioBuffer.isEmpty()) return false
        var sumSquares = 0.0
        for (sample in audioBuffer) {
            sumSquares += sample * sample
        }
        val rms = Math.sqrt(sumSquares / audioBuffer.size).toFloat()
        val hasSpeech = rms >= speechThresholdRms
        if (hasSpeech) {
            lastSpeechTimestamp = System.currentTimeMillis()
        }
        return hasSpeech
    }

    override fun getSilenceDurationMs(): Long {
        if (lastSpeechTimestamp == 0L) return 0L
        return System.currentTimeMillis() - lastSpeechTimestamp
    }

    override fun reset() {
        lastSpeechTimestamp = 0L
    }
}
