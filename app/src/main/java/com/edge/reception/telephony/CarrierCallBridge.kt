package com.edge.reception.telephony

import android.content.Context
import android.util.Log
import com.edge.reception.ai.ASREngine
import com.edge.reception.ai.EnergyVADEngine
import com.edge.reception.ai.LLMEngine
import com.edge.reception.ai.LocalLLMEngine
import com.edge.reception.ai.MultilingualASREngine
import com.edge.reception.ai.TTSEngine
import com.edge.reception.audio.StereoCallWavRecorder
import com.edge.reception.audio.WavTo16kHzPcmConverter
import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.local.entity.CallEntity
import com.edge.reception.data.local.entity.TranscriptTurnEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class TelephonyGatewayStatus(
    val configured: Boolean,
    val sipRegistered: Boolean,
    val inboundDid: String?,
    val sipRegistrarHost: String?,
    val missingPrerequisites: List<String>
)

data class PublicProviderStatus(
    val provider: String,
    val state: String,
    val configured: Boolean,
    val publicNumberProvisioned: Boolean,
    val gatewayReachability: String,
    val inboundDid: String?,
    val missingPrerequisites: List<String>
)

class CarrierCallBridge(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var recorder: StereoCallWavRecorder? = null
    private var recordingFile: File? = null

    private val ttsEngine = TTSEngine(context)
    @Volatile
    private var isTtsReady = false
    private val asrEngine: ASREngine = MultilingualASREngine()
    private val vadEngine = EnergyVADEngine(speechThresholdRms = 350f, silenceHangoverMs = 1200L)
    private val llmEngine: LLMEngine = LocalLLMEngine()

    init {
        ttsEngine.init { ready ->
            isTtsReady = ready
            Log.i("CarrierCallBridge", "TTSEngine initialized: $ready")
        }
    }

    @Volatile
    private var isCallActive = false
    @Volatile
    private var isAiProcessingOrSpeaking = false
    private var currentCallId: String = ""
    private var callerNumber: String = "PSTN Caller"
    private var callStartTime: Long = 0L
    private var isCurrentCallTestSession: Boolean = true

    private val callerAudioBuffer = ByteArrayOutputStream()
    @Volatile
    private var hasDetectedCallerSpeech = false
    private var silenceCheckJob: Job? = null
    private val turns = mutableListOf<TranscriptTurnEntity>()
    private val dialogueHistory = mutableListOf<Pair<String, String>>()
    private var lastLanguage = "English"

    suspend fun fetchGatewayStatus(backendBaseUrl: String = "http://127.0.0.1:8000"): TelephonyGatewayStatus = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$backendBaseUrl/v1/telephony/status").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = JSONObject(res.body?.string() ?: "{}")
                val configured = json.optBoolean("configured", false)
                val sipRegistered = json.optBoolean("sip_registered", false)
                val inboundDid = if (json.isNull("inbound_did")) null else json.optString("inbound_did")
                val sipRegistrarHost = if (json.isNull("sip_registrar_host")) null else json.optString("sip_registrar_host")
                val missingArr = json.optJSONArray("missing_prerequisites")
                val missingList = mutableListOf<String>()
                if (missingArr != null) {
                    for (i in 0 until missingArr.length()) {
                        missingList.add(missingArr.getString(i))
                    }
                }
                TelephonyGatewayStatus(configured, sipRegistered, inboundDid, sipRegistrarHost, missingList)
            } else {
                TelephonyGatewayStatus(false, false, null, null, listOf("GATEWAY_HTTP_${res.code}"))
            }
        } catch (e: Exception) {
            Log.e("CarrierCallBridge", "Error querying gateway status: ${e.message}")
            TelephonyGatewayStatus(false, false, null, null, listOf("GATEWAY_CONNECTION_ERROR"))
        }
    }

    suspend fun fetchProviderStatus(backendBaseUrl: String = "http://127.0.0.1:8000"): PublicProviderStatus = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$backendBaseUrl/v1/telephony/provider/status").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = JSONObject(res.body?.string() ?: "{}")
                val provider = json.optString("provider", "exotel")
                val state = json.optString("state", "NOT_CONFIGURED")
                val configured = json.optBoolean("configured", false)
                val pubNumProv = json.optBoolean("public_number_provisioned", false)
                val gwReach = json.optString("gateway_reachability", "UNCONFIGURED")
                val inboundDid = if (json.isNull("inbound_did")) null else json.optString("inbound_did")
                val missingArr = json.optJSONArray("missing_prerequisites")
                val missingList = mutableListOf<String>()
                if (missingArr != null) {
                    for (i in 0 until missingArr.length()) {
                        missingList.add(missingArr.getString(i))
                    }
                }
                PublicProviderStatus(provider, state, configured, pubNumProv, gwReach, inboundDid, missingList)
            } else {
                PublicProviderStatus("exotel", "HTTP_ERROR_${res.code}", false, false, "ERROR", null, listOf("HTTP_${res.code}"))
            }
        } catch (e: Exception) {
            Log.e("CarrierCallBridge", "Error querying provider status: ${e.message}")
            PublicProviderStatus("exotel", "GATEWAY_UNREACHABLE", false, false, "ERROR", null, listOf("GATEWAY_CONNECTION_ERROR"))
        }
    }

    fun startCall(
        callId: String,
        token: String,
        gatewayHost: String = "127.0.0.1:8000",
        isTestCall: Boolean = true,
        onStateChange: (String) -> Unit = {}
    ) {
        if (isCallActive) {
            endCall()
        }

        currentCallId = callId
        isCurrentCallTestSession = isTestCall
        isCallActive = true
        callStartTime = System.currentTimeMillis()
        hasDetectedCallerSpeech = false
        turns.clear()
        dialogueHistory.clear()
        callerAudioBuffer.reset()

        val recordDir = File(context.filesDir, "recordings")
        recordingFile = File(recordDir, "carrier_call_${callId}.wav")
        recorder = StereoCallWavRecorder(recordingFile!!)
        recorder?.start()

        val url = "ws://$gatewayHost/ws/telephony/carrier-media?call_id=$callId&token=$token"
        Log.i("CarrierCallBridge", "Connecting to carrier media channel at $url")

        val request = Request.Builder().url(url).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("CarrierCallBridge", "Connected to carrier media bridge for call $callId")
                onStateChange("CONNECTED")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControlMessage(text, onStateChange)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingCallerPcm(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("CarrierCallBridge", "Carrier media closing: $code / $reason")
                endCall()
                onStateChange("CLOSED")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("CarrierCallBridge", "Carrier media error: ${t.message}")
                endCall()
                onStateChange("ERROR: ${t.message}")
            }
        })
    }

    private fun handleControlMessage(text: String, onStateChange: (String) -> Unit) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            Log.i("CarrierCallBridge", "Received control message: $type")

            when (type) {
                "PEER_CONNECTED" -> {
                    callerNumber = json.optString("caller_number", "PSTN Caller")
                    onStateChange("IN_CALL")
                    deliverGreeting()
                }
                "CALLER_SPEECH_DONE" -> {
                    silenceCheckJob?.cancel()
                    processCallerTurn()
                }
                "CALL_ENDED" -> {
                    endCall()
                    onStateChange("CALL_ENDED")
                }
            }
        } catch (e: Exception) {
            Log.e("CarrierCallBridge", "Error parsing control JSON: ${e.message}")
        }
    }

    private fun deliverGreeting() {
        if (!isCallActive || isAiProcessingOrSpeaking) return
        isAiProcessingOrSpeaking = true

        scope.launch {
            val greetingWav = File(context.cacheDir, "carrier_greeting_${System.currentTimeMillis()}.wav")
            try {
                var waitCount = 0
                while (!isTtsReady && waitCount < 30) {
                    delay(100)
                    waitCount++
                }

                val db = AppDatabase.getInstance(context)
                val profile = db.businessDao().getProfile()
                val bizName = profile?.businessName ?: "Edge Prime Realty Bangalore"
                val lang = profile?.defaultLanguage ?: "English"
                val greetingText = profile?.openingGreeting ?: "Hello, thank you for calling $bizName. How can I assist you with our luxury flats, villa plots, and land investments today?"

                Log.i("CarrierCallBridge", "Delivering greeting to carrier caller: $greetingText")

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "GREETING_START")
                    put("text", greetingText)
                    put("language", lang)
                }.toString())

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_START")
                }.toString())

                val synthSuccess = ttsEngine.synthesizeToFile(greetingText, greetingWav, lang)
                if (synthSuccess && greetingWav.exists()) {
                    val pcm16k = WavTo16kHzPcmConverter.decodeAndResampleTo16kMono(greetingWav)
                    streamPcmToGateway(pcm16k)
                }

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentCallId,
                        speaker = "EDGE",
                        recognizedText = greetingText,
                        englishTranslation = greetingText,
                        detectedLanguage = lang,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("EDGE" to greetingText)

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_STOP")
                }.toString())

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "GREETING_COMPLETED")
                }.toString())

            } catch (e: Throwable) {
                Log.e("CarrierCallBridge", "Exception in deliverGreeting: ${e.message}", e)
            } finally {
                greetingWav.delete()
                isAiProcessingOrSpeaking = false
            }
        }
    }

    private fun handleIncomingCallerPcm(pcmBytes: ByteArray) {
        if (!isCallActive || isAiProcessingOrSpeaking) return

        // Write caller audio to Left stereo channel (Right channel padded with silence)
        recorder?.writeMonoChunkAsStereo(pcmBytes, isCallerLeftChannel = true)
        callerAudioBuffer.write(pcmBytes)

        val shortCount = pcmBytes.size / 2
        val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            shorts[i] = shortBuf.get(i)
        }

        val hasSpeech = vadEngine.isSpeechDetected(shorts)
        if (hasSpeech || callerAudioBuffer.size() >= 32000) {
            hasDetectedCallerSpeech = true
            silenceCheckJob?.cancel()
            scheduleSilenceTimeout()
        }
    }

    private fun scheduleSilenceTimeout() {
        silenceCheckJob = scope.launch {
            delay(800L)
            if (hasDetectedCallerSpeech && isCallActive && !isAiProcessingOrSpeaking) {
                processCallerTurn()
            }
        }
    }

    private fun processCallerTurn() {
        if (isAiProcessingOrSpeaking) return
        isAiProcessingOrSpeaking = true
        hasDetectedCallerSpeech = false

        val callerPcm = callerAudioBuffer.toByteArray()
        callerAudioBuffer.reset()
        if (callerPcm.size < 3200) {
            isAiProcessingOrSpeaking = false
            return
        }

        scope.launch {
            val replyWav = File(context.cacheDir, "carrier_reply_${System.currentTimeMillis()}.wav")
            try {
                val asrResult = asrEngine.transcribe(callerPcm)
                lastLanguage = asrResult.detectedLanguage
                Log.i("CarrierCallBridge", "Carrier Caller Speech: '${asrResult.transcript}' [${asrResult.detectedLanguage}]")

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "TRANSCRIPT")
                    put("speaker", "Caller")
                    put("text", asrResult.transcript)
                    put("language", asrResult.detectedLanguage)
                }.toString())

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentCallId,
                        speaker = "CUSTOMER",
                        recognizedText = asrResult.transcript,
                        englishTranslation = asrResult.englishTranslation,
                        detectedLanguage = asrResult.detectedLanguage,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                dialogueHistory.add("CUSTOMER" to asrResult.transcript)

                val db = AppDatabase.getInstance(context)
                val profile = db.businessDao().getProfile()
                val bizName = profile?.businessName ?: "Edge Realty & Services"
                val bizType = profile?.businessType ?: "Real Estate"

                val turnResponse = llmEngine.generateReply(
                    businessType = bizType,
                    businessName = bizName,
                    dialogueHistory = dialogueHistory,
                    latestCustomerSpeech = asrResult.transcript,
                    language = asrResult.detectedLanguage
                )
                val replyText = turnResponse.replyText
                val replyLang = turnResponse.replyLanguage
                Log.i("CarrierCallBridge", "Generated AI Reply: '$replyText' [$replyLang]")

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "AI_SPEAKING_START")
                }.toString())

                activeWebSocket?.send(JSONObject().apply {
                    put("type", "TRANSCRIPT")
                    put("speaker", "AI")
                    put("text", replyText)
                    put("language", replyLang)
                }.toString())

                val synthSuccess = ttsEngine.synthesizeToFile(replyText, replyWav, replyLang)
                if (synthSuccess && replyWav.exists()) {
                    val pcm16k = WavTo16kHzPcmConverter.decodeAndResampleTo16kMono(replyWav)
                    streamPcmToGateway(pcm16k)
                }

                turns.add(
                    TranscriptTurnEntity(
                        callId = currentCallId,
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

            } catch (e: Throwable) {
                Log.e("CarrierCallBridge", "Error in caller turn: ${e.message}", e)
            } finally {
                replyWav.delete()
                isAiProcessingOrSpeaking = false
            }
        }
    }

    private suspend fun streamPcmToGateway(pcmBytes: ByteArray) {
        val chunkSize = 3200
        var offset = 0
        while (offset < pcmBytes.size && isCallActive) {
            val len = (pcmBytes.size - offset).coerceAtMost(chunkSize)
            val chunk = pcmBytes.copyOfRange(offset, offset + len)

            recorder?.writeMonoChunkAsStereo(chunk, isCallerLeftChannel = false)
            activeWebSocket?.send(chunk.toByteString())

            offset += len
            delay(40L)
        }
    }

    fun endCall() {
        if (!isCallActive) return
        isCallActive = false
        silenceCheckJob?.cancel()

        try {
            recorder?.closeAndFinalizeHeader()
        } catch (e: Exception) {
            Log.e("CarrierCallBridge", "Error closing recorder: ${e.message}")
        }

        val duration = ((System.currentTimeMillis() - callStartTime) / 1000).toInt().coerceAtLeast(1)

        scope.launch {
            val db = AppDatabase.getInstance(context)
            val profile = db.businessDao().getProfile()
            val bizType = profile?.businessType ?: "Real Estate"

            val analysis = llmEngine.analyzeCall(bizType, dialogueHistory)

            // Saved with appropriate test session isolation flag
            val callEntity = CallEntity(
                id = currentCallId,
                callerId = callerNumber,
                timestamp = callStartTime,
                durationSeconds = duration,
                language = lastLanguage,
                isLead = analysis.isLead,
                outcome = analysis.outcome,
                summary = analysis.summary,
                audioRecordingPath = recordingFile?.absolutePath,
                isDemo = false,
                isTestSession = isCurrentCallTestSession,
                synced = false
            )
            db.callDao().insertCall(callEntity)
            db.transcriptDao().insertTurns(turns)
            Log.i("CarrierCallBridge", "Saved carrier call $currentCallId to local DB (isTestSession=$isCurrentCallTestSession, duration=${duration}s)")
        }

        try {
            activeWebSocket?.close(1000, "Call finished")
        } catch (e: Exception) {
            // Ignore
        }
        activeWebSocket = null
    }
}
