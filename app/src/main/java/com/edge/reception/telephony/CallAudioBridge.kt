package com.edge.reception.telephony

interface CallAudioBridge {
    fun startCapture(onAudioData: (ShortArray) -> Unit)
    fun playAudio(pcmSamples: ShortArray)
    fun stop()
    fun isTwoWayTelephonySupported(): Boolean
    fun getBridgeType(): String
}

class LocalLoopAudioBridge : CallAudioBridge {
    private var isRunning = false

    override fun startCapture(onAudioData: (ShortArray) -> Unit) {
        isRunning = true
    }

    override fun playAudio(pcmSamples: ShortArray) {
        // Output through speaker/earpiece
    }

    override fun stop() {
        isRunning = false
    }

    override fun isTwoWayTelephonySupported(): Boolean = false

    override fun getBridgeType(): String = "Local Audio Loop / Microphone Test Bridge"
}

class VoipSipAudioBridge(private val sipGatewayHost: String, private val carrierBridge: CarrierCallBridge? = null) : CallAudioBridge {
    private var isStreaming = false

    override fun startCapture(onAudioData: (ShortArray) -> Unit) {
        isStreaming = true
    }

    override fun playAudio(pcmSamples: ShortArray) {
        // Output routed through carrier media stream
    }

    override fun stop() {
        isStreaming = false
        carrierBridge?.endCall()
    }

    override fun isTwoWayTelephonySupported(): Boolean = true

    override fun getBridgeType(): String = "Carrier Call-Forwarded SIP/PSTN Gateway ($sipGatewayHost)"
}
