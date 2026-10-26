package com.edge.reception.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.edge.reception.ai.ASREngine
import com.edge.reception.ai.EnergyVADEngine
import com.edge.reception.ai.LLMEngine
import com.edge.reception.ai.LocalLLMEngine
import com.edge.reception.ai.MultilingualASREngine
import com.edge.reception.ai.TTSEngine
import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.local.entity.CallEntity
import com.edge.reception.data.local.entity.TranscriptTurnEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class LocalVoiceTestManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var recorder: StereoCallWavRecorder? = null
    private var recordingFile: File? = null

    private val ttsEngine = TTSEngine(context)
    private var isTtsReady = false
    private val asrEngine: ASREngine = MultilingualASREngine()
    private val vadEngine = EnergyVADEngine(speechThresholdRms = 350f, silenceHangoverMs = 1200L)
    private val llmEngine: LLMEngine = LocalLLMEngine()
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    init {
        ttsEngine.init { ready ->
            isTtsReady = ready
            Log.i("LocalVoiceTest", "TTSEngine initialized: $ready")
        }
    }

    @Volatile
    private var isSessionActive = false
    @Volatile
    private var isAiProcessingOrSpeaking = false
    private var currentSessionId: String = ""
    private var sessionStartTime: Long = 0L

    private val callerAudioBuffer = ByteArrayOutputStream()
    @Volatile
    private var hasDetectedCallerSpeech = false
    private var silenceCheckJob: Job? = null
    private val turns = mutableListOf<TranscriptTurnEntity>()
    private val dialogueHistory = mutableListOf<Pair<String, String>>()
    private var lastLanguage = "English"
    private var isLeadOutcome = false
    private var sessionLangOverride: String? = null
    private var sessionPersonaOverride: String? = null
    private var sessionBizTypeOverride: String? = null
    private var greetingDelivered: Boolean = false
    private var currentSpeakingJob: Job? = null

    fun interruptCurrentAiTurn() {
        Log.i("LocalVoiceTest", "⚡ Interrupting AI turn: cancelling job, resetting buffer")
        currentSpeakingJob?.cancel()
        currentSpeakingJob = null
        callerAudioBuffer.reset()
        hasDetectedCallerSpeech = false
        silenceCheckJob?.cancel()
        isAiProcessingOrSpeaking = false

        activeWebSocket?.send(JSONObject().apply {
            put("type", "AI_SPEAKING_STOP")
        }.toString())
    }

    fun isCurrentSession(sessionId: String): Boolean {
        return isSessionActive && currentSessionId == sessionId
    }

    fun startSession(sessionId: String, token: String, preferredHost: String = "127.0.0.1", onStateChange: (String) -> Unit = {}) {
        if (isSessionActive) {
            if (currentSessionId == sessionId) {
                Log.w("LocalVoiceTest", "Session $sessionId is already active; ignoring duplicate start request.")
                return
            }
            Log.w("LocalVoiceTest", "Ending existing session $currentSessionId before starting new session $sessionId")
            endSession()
        }

        currentSessionId = sessionId
        isSessionActive = true
        greetingDelivered = false
        sessionStartTime = System.currentTimeMillis()
        turns.clear()
        dialogueHistory.clear()
        callerAudioBuffer.reset()
        hasDetectedCallerSpeech = false

        try {
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Edge:LocalVoiceTest").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("LocalVoiceTest", "Error acquiring wakeLock: ${e.message}")
        }

        val recDir = File(context.filesDir, "recordings")
        recDir.mkdirs()
        recordingFile = File(recDir, "test_session_${sessionId}.wav")
        recorder = StereoCallWavRecorder(recordingFile!!, sampleRate = 16000).apply {
            start()
        }

        val wsUrl = "ws://$preferredHost:8000/ws/device-audio?session_id=$sessionId&token=$token"
        Log.i("LocalVoiceTest", "Connecting to device-audio WebSocket at $wsUrl")
        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("LocalVoiceTest", "Connected to device-audio WebSocket at $preferredHost for session $sessionId")
                onStateChange("CONNECTED")
                val readyMsg = JSONObject().apply {
                    put("type", "DEVICE_READY")
                    put("session_id", sessionId)
                    put("sample_rate", 16000)
                }
                webSocket.send(readyMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControlMessage(text, onStateChange)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingCallerPcm(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("LocalVoiceTest", "WebSocket closing: $code / $reason")
                endSession()
                onStateChange("CLOSED")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("LocalVoiceTest", "WebSocket error: ${t.message}")
                endSession()
                onStateChange("ERROR: ${t.message}")
            }
        })
    }

    private fun handleControlMessage(text: String, onStateChange: (String) -> Unit) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            Log.i("LocalVoiceTest", "Received control message: $type")

            when (type) {
                "INTERRUPT" -> {
                    Log.i("LocalVoiceTest", "⚡ Received INTERRUPT from caller, cutting off AI speech")
                    interruptCurrentAiTurn()
                }
                "START", "PEER_CONNECTED" -> {
                    if (json.has("language")) {
                        val l = json.optString("language")
                        if (l.isNotEmpty() && !l.lowercase().contains("auto")) {
                            sessionLangOverride = l
                        }
                    }
                    if (json.has("voice_persona")) {
                        val p = json.optString("voice_persona")
                        if (p.isNotEmpty()) {
                            sessionPersonaOverride = p
                            ttsEngine.setVoicePersona(p)
                        }
                    }
                    if (json.has("business_type")) {
                        val b = json.optString("business_type")
                        if (b.isNotEmpty()) sessionBizTypeOverride = b
                    }
                    onStateChange("IN_CALL")
                    if (!greetingDelivered) {
                        greetingDelivered = true
                        deliverGreeting()
                    }
                }
                "CALLER_SPEECH_DONE" -> {
                    Log.i("LocalVoiceTest", "Explicit CALLER_SPEECH_DONE received, triggering processCallerTurn")
                    silenceCheckJob?.cancel()
                    processCallerTurn()
                }
                "CALLER_SPEECH" -> {
                    val speechText = json.optString("text")
                    Log.i("LocalVoiceTest", "Explicit CALLER_SPEECH received: $speechText")
                    if (speechText.isNotEmpty()) {
                        silenceCheckJob?.cancel()
                        processCallerSpeechText(speechText)
                    }
                }
                "CALL_ENDED" -> {
                    endSession()
                    onStateChange("CALL_ENDED")
                }
            }
        } catch (e: Exception) {
            Log.e("LocalVoiceTest", "Error parsing control JSON: ${e.message}")
        }
    }

    private fun deliverGreeting() {
        if (!isSessionActive || isAiProcessingOrSpeaking) return
        isAiProcessingOrSpeaking = true

        currentSpeakingJob = scope.launch {
            val greetingWav = File(context.cacheDir, "greeting_${System.currentTimeMillis()}.wav")
            try {
                var waitCount = 0
                while (!isTtsReady && waitCount < 80) {
                    delay(100)
                    waitCount++
                }

                val db = AppDatabase.getInstance(context)
                val profile = db.businessDao().getProfile()
                val bizName = profile?.businessName ?: "Edge Realty & Services"
                val bizType = sessionBizTypeOverride ?: profile?.businessType ?: "Real Estate"
                val lang = sessionLangOverride ?: profile?.defaultLanguage ?: "English"

                val greetingText = when (bizType.lowercase()) {
                    "supermarket" -> "Hello, thank you for contacting $bizName. How can I help you today?"
                    "medical shop / pharmacy", "pharmacy" -> "Hello, welcome to $bizName Pharmacy. Are you looking for prescription refills or healthcare supplies?"
                    "customer support" -> "Hello, thank you for reaching $bizName customer support. How can I assist you today?"
                    else -> "Hello! Thank you for calling Edge Prime Realty Bangalore. How can I assist you with our luxury flats, villa plots, or land prices today?"
                }

                Log.i("LocalVoiceTest", "Delivering greeting ($bizType, $lang): $greetingText")

                // Send control notification
                activeWebSocket?.send(JSONObject().apply {
                    put("type", "GREETING_START")
                    put("text", greetingText)
                    put("language", lang)
                }.toString())

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_START")
                }.toString())

                val synthSuccess = ttsEngine.synthesizeToFile(greetingText, greetingWav, lang)
                Log.i("LocalVoiceTest", "Greeting synthesis completed: success=$synthSuccess, bytes=${greetingWav.length()}")

                if (synthSuccess && greetingWav.exists() && isAiProcessingOrSpeaking) {
                    val pcm16k = WavTo16kHzPcmConverter.decodeAndResampleTo16kMono(greetingWav)
                    Log.i("LocalVoiceTest", "Greeting resampled: ${pcm16k.size} bytes. Streaming to caller...")
                    streamPcmToBrowser(pcm16k, isCaller = false)
                }

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentSessionId,
                        speaker = "EDGE",
                        recognizedText = greetingText,
                        englishTranslation = greetingText,
                        detectedLanguage = lang,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("EDGE" to greetingText)

                Log.i("LocalVoiceTest", "Greeting streaming completed. Sending GREETING_COMPLETED.")
                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_STOP")
                }.toString())

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "GREETING_COMPLETED")
                }.toString())

            } catch (e: Throwable) {
                Log.e("LocalVoiceTest", "Exception in deliverGreeting: ${e.message}", e)
            } finally {
                greetingWav.delete()
                isAiProcessingOrSpeaking = false
            }
        }
    }

    private fun handleIncomingCallerPcm(pcmBytes: ByteArray) {
        if (!isSessionActive) return

        // Write caller audio to Left stereo channel (with silence on Right)
        recorder?.writeMonoChunkAsStereo(pcmBytes, isCallerLeftChannel = true)

        // Convert byte buffer to ShortArray for Energy VAD check
        val shortCount = pcmBytes.size / 2
        val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            shorts[i] = shortBuf.get(i)
        }

        val hasSpeech = vadEngine.isSpeechDetected(shorts)

        // BARGE-IN: If AI is speaking and user speaks, immediately interrupt AI!
        if (hasSpeech && isAiProcessingOrSpeaking) {
            Log.i("LocalVoiceTest", "⚡ Caller voice barge-in detected! Halting AI playback.")
            interruptCurrentAiTurn()
        }

        // Buffer caller audio for VAD and ASR
        callerAudioBuffer.write(pcmBytes)

        if (hasSpeech || callerAudioBuffer.size() >= 32000) {
            hasDetectedCallerSpeech = true
            silenceCheckJob?.cancel()
            scheduleSilenceTimeout()
        }
    }

    private fun scheduleSilenceTimeout() {
        silenceCheckJob = scope.launch {
            delay(800L) // 800ms silence hangover
            Log.i("LocalVoiceTest", "Silence timeout check: hasSpeech=$hasDetectedCallerSpeech, active=$isSessionActive, aiSpeaking=$isAiProcessingOrSpeaking")
            if (hasDetectedCallerSpeech && isSessionActive && !isAiProcessingOrSpeaking) {
                processCallerTurn()
            }
        }
    }

    private fun processCallerTurn() {
        Log.i("LocalVoiceTest", "processCallerTurn invoked: isAiProcessingOrSpeaking=$isAiProcessingOrSpeaking, buffered=${callerAudioBuffer.size()} bytes")
        if (isAiProcessingOrSpeaking) return
        isAiProcessingOrSpeaking = true
        hasDetectedCallerSpeech = false

        val callerPcm = callerAudioBuffer.toByteArray()
        callerAudioBuffer.reset()
        Log.i("LocalVoiceTest", "Processing caller turn with ${callerPcm.size} bytes...")

        if (callerPcm.size < 3200) { // Less than 100ms
            isAiProcessingOrSpeaking = false
            return
        }

        currentSpeakingJob = scope.launch {
            val replyWav = File(context.cacheDir, "reply_${System.currentTimeMillis()}.wav")
            try {
                // 1. Multilingual Speech Recognition
                val asrResult = asrEngine.transcribe(callerPcm)
                lastLanguage = asrResult.detectedLanguage
                Log.i("LocalVoiceTest", "Caller Speech Transcribed: '${asrResult.transcript}' [${asrResult.detectedLanguage}]")

                // Send transcript to browser
                activeWebSocket?.send(JSONObject().apply {
                    put("type", "TRANSCRIPT")
                    put("speaker", "Caller")
                    put("text", asrResult.transcript)
                    put("language", asrResult.detectedLanguage)
                }.toString())

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentSessionId,
                        speaker = "CUSTOMER",
                        recognizedText = asrResult.transcript,
                        englishTranslation = asrResult.englishTranslation,
                        detectedLanguage = asrResult.detectedLanguage,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("CUSTOMER" to asrResult.transcript)

                // 2. On-Device LLM Reasoning
                val db = AppDatabase.getInstance(context)
                val profile = db.businessDao().getProfile()
                val bizName = profile?.businessName ?: "Edge Realty & Services"
                val bizType = sessionBizTypeOverride ?: profile?.businessType ?: "Real Estate"
                val promptLang = sessionLangOverride ?: asrResult.detectedLanguage

                val turnResponse = llmEngine.generateReply(
                    businessType = bizType,
                    businessName = bizName,
                    dialogueHistory = dialogueHistory,
                    latestCustomerSpeech = asrResult.transcript,
                    language = promptLang
                )

                val replyText = turnResponse.replyText
                val replyLang = sessionLangOverride ?: turnResponse.replyLanguage

                Log.i("LocalVoiceTest", "AI Generated Reply: '$replyText' [${replyLang}]")

                // Send transcript to browser
                activeWebSocket?.send(JSONObject().apply {
                    put("type", "TRANSCRIPT")
                    put("speaker", "AI")
                    put("text", replyText)
                    put("language", replyLang)
                }.toString())

                // 3. Dynamic TTS Generation
                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_START")
                    put("text", replyText)
                    put("language", replyLang)
                }.toString())

                val synthSuccess = ttsEngine.synthesizeToFile(replyText, replyWav, replyLang)
                Log.i("LocalVoiceTest", "Reply synthesis completed: success=$synthSuccess, bytes=${replyWav.length()}")
                if (replyWav.exists() && replyWav.length() > 44 && isAiProcessingOrSpeaking) {
                    val pcm16k = WavTo16kHzPcmConverter.decodeAndResampleTo16kMono(replyWav)
                    Log.i("LocalVoiceTest", "Reply resampled: ${pcm16k.size} bytes. Streaming to caller...")
                    streamPcmToBrowser(pcm16k, isCaller = false)
                } else if (isAiProcessingOrSpeaking) {
                    Log.w("LocalVoiceTest", "Reply WAV empty or failed, synthesizing comfort tone frames for caller")
                    val fallback = ByteArray(16000 * 2) { 0 }
                    streamPcmToBrowser(fallback, isCaller = false)
                }

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentSessionId,
                        speaker = "EDGE",
                        recognizedText = replyText,
                        englishTranslation = replyText,
                        detectedLanguage = replyLang,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("EDGE" to replyText)

                Log.i("LocalVoiceTest", "Reply streaming completed.")
                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_STOP")
                }.toString())

            } catch (e: Throwable) {
                Log.e("LocalVoiceTest", "Error in caller turn processing: ${e.message}", e)
            } finally {
                replyWav.delete()
                isAiProcessingOrSpeaking = false
            }
        }
    }

    private fun playOnDeviceSpeaker(wavFile: File) {
        // Disabled during browser voice sessions to prevent acoustic feedback into laptop mic
    }

    private fun processCallerSpeechText(text: String) {
        if (isAiProcessingOrSpeaking) {
            Log.i("LocalVoiceTest", "⚡ Caller speech text interrupted prior AI turn")
            interruptCurrentAiTurn()
        }
        isAiProcessingOrSpeaking = true
        currentSpeakingJob = scope.launch {
            val replyWav = File(context.cacheDir, "reply_${System.currentTimeMillis()}.wav")
            try {
                val detectedLang = asrEngine.detectLanguage(text)
                lastLanguage = detectedLang

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "TRANSCRIPT")
                    put("speaker", "Caller")
                    put("text", text)
                    put("language", detectedLang)
                }.toString())

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentSessionId,
                        speaker = "CUSTOMER",
                        recognizedText = text,
                        englishTranslation = text,
                        detectedLanguage = detectedLang,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("CUSTOMER" to text)

                val db = AppDatabase.getInstance(context)
                val profile = db.businessDao().getProfile()
                val bizName = profile?.businessName ?: "Edge Realty & Services"
                val bizType = sessionBizTypeOverride ?: profile?.businessType ?: "Real Estate"
                val promptLang = sessionLangOverride ?: detectedLang

                val turnResponse = llmEngine.generateReply(
                    businessType = bizType,
                    businessName = bizName,
                    dialogueHistory = dialogueHistory,
                    latestCustomerSpeech = text,
                    language = promptLang
                )

                val replyText = turnResponse.replyText
                val replyLang = sessionLangOverride ?: turnResponse.replyLanguage

                Log.i("LocalVoiceTest", "AI Generated Reply: '$replyText' [$replyLang]")

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "TRANSCRIPT")
                    put("speaker", "AI")
                    put("text", replyText)
                    put("language", replyLang)
                }.toString())

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_START")
                    put("text", replyText)
                    put("language", replyLang)
                }.toString())

                val synthSuccess = ttsEngine.synthesizeToFile(replyText, replyWav, replyLang)
                if (synthSuccess && replyWav.exists() && isAiProcessingOrSpeaking) {
                    val pcm16k = WavTo16kHzPcmConverter.decodeAndResampleTo16kMono(replyWav)
                    streamPcmToBrowser(pcm16k, isCaller = false)
                }

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentSessionId,
                        speaker = "EDGE",
                        recognizedText = replyText,
                        englishTranslation = replyText,
                        detectedLanguage = replyLang,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("EDGE" to replyText)

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_STOP")
                }.toString())
            } catch (e: Exception) {
                Log.e("LocalVoiceTest", "Error processing caller speech text: ${e.message}", e)
            } finally {
                replyWav.delete()
                isAiProcessingOrSpeaking = false
            }
        }
    }

    private suspend fun streamPcmToBrowser(pcmBytes: ByteArray, isCaller: Boolean) {
        val chunkSize = 3200 // 100ms at 16kHz mono (1600 samples * 2 bytes)
        var offset = 0
        while (offset < pcmBytes.size && isSessionActive && isAiProcessingOrSpeaking) {
            val len = (pcmBytes.size - offset).coerceAtMost(chunkSize)
            val chunk = pcmBytes.copyOfRange(offset, offset + len)

            // Write into Stereo WAV recorder (AI on Right channel)
            recorder?.writeMonoChunkAsStereo(chunk, isCallerLeftChannel = isCaller)

            // Send binary frame over WebSocket to MacBook browser
            val sent = activeWebSocket?.send(chunk.toByteString())
            if (sent != true) {
                Log.w("LocalVoiceTest", "activeWebSocket send returned false (offset=$offset)")
            }

            offset += len
            delay(90) // Match real-time pacing so caller can barge-in cleanly
        }
        Log.i("LocalVoiceTest", "streamPcmToBrowser completed: streamed $offset bytes")
    }

    fun endSession() {
        if (!isSessionActive) return
        isSessionActive = false
        silenceCheckJob?.cancel()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e("LocalVoiceTest", "Error releasing wakeLock: ${e.message}")
        }

        try {
            activeWebSocket?.cancel()
            activeWebSocket = null
        } catch (e: Exception) {
            Log.e("LocalVoiceTest", "Error canceling activeWebSocket: ${e.message}")
        }

        try {
            recorder?.closeAndFinalizeHeader()
        } catch (e: Exception) {
            Log.e("LocalVoiceTest", "Error finalizing recording: ${e.message}")
        }

        val duration = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt().coerceAtLeast(1)

        // Run call analysis
        scope.launch {
            val db = AppDatabase.getInstance(context)
            val profile = db.businessDao().getProfile()
            val bizType = profile?.businessType ?: "Real Estate"

            val analysis = llmEngine.analyzeCall(bizType, dialogueHistory)
            isLeadOutcome = analysis.isLead

            val callEntity = CallEntity(
                id = currentSessionId,
                callerId = "Local Mac Test Caller",
                timestamp = sessionStartTime,
                durationSeconds = duration,
                language = lastLanguage,
                isLead = isLeadOutcome,
                outcome = analysis.outcome,
                summary = analysis.summary,
                audioRecordingPath = recordingFile?.absolutePath,
                isDemo = false,
                isTestSession = true,
                synced = true
            )
            db.callDao().insertCall(callEntity)
            db.transcriptDao().insertTurns(turns)
            Log.i("LocalVoiceTest", "Saved local voice test session $currentSessionId (isTestSession=true, duration=${duration}s, outcome=${analysis.outcome})")
        }

        try {
            activeWebSocket?.close(1000, "Session completed")
        } catch (e: Exception) {
            // Ignore
        }
        activeWebSocket = null
    }
}
