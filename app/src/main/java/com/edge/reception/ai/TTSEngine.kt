package com.edge.reception.ai

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

data class VoicePersonaInfo(
    val id: String,
    val label: String,
    val gender: String,
    val description: String,
    val pitch: Float,
    val speechRate: Float
)

class TTSEngine(private val context: Context) {

    companion object {
        const val PERSONA_FEMALE = "SOUTH_INDIAN_FEMALE"
        const val PERSONA_MALE = "SOUTH_INDIAN_MALE"

        val AVAILABLE_PERSONAS = listOf(
            VoicePersonaInfo(
                id = PERSONA_FEMALE,
                label = "Female (South Indian)",
                gender = "Female",
                description = "Warm, professional, naturally paced Indian receptionist voice",
                pitch = 1.05f,
                speechRate = 0.95f
            ),
            VoicePersonaInfo(
                id = PERSONA_MALE,
                label = "Male (South Indian)",
                gender = "Male",
                description = "Polite, authoritative, clear Indian business receptionist voice",
                pitch = 0.85f,
                speechRate = 0.95f
            )
        )
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var activePersona: String = PERSONA_FEMALE

    fun init(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status ->
            isInitialized = (status == TextToSpeech.SUCCESS)
            if (isInitialized) {
                applyPersona(activePersona)
            }
            onReady(isInitialized)
        }
    }

    fun setVoicePersona(personaId: String) {
        activePersona = if (personaId == PERSONA_MALE) PERSONA_MALE else PERSONA_FEMALE
        if (isInitialized) {
            applyPersona(activePersona)
        }
    }

    fun getActivePersona(): String = activePersona

    private fun applyPersona(personaId: String) {
        val info = AVAILABLE_PERSONAS.find { it.id == personaId } ?: AVAILABLE_PERSONAS[0]
        tts?.setPitch(info.pitch)
        tts?.setSpeechRate(info.speechRate)

        // Try selecting voice matching gender if engine supports voice list
        try {
            val voices = tts?.voices
            if (!voices.isNullOrEmpty()) {
                val targetNameFragment = if (personaId == PERSONA_FEMALE) "female" else "male"
                val match = voices.find { voice ->
                    val name = voice.name.lowercase()
                    name.contains(targetNameFragment) || (personaId == PERSONA_FEMALE && name.contains("-a-") || name.contains("-c-") || name.contains("-e-")) || (personaId == PERSONA_MALE && (name.contains("-b-") || name.contains("-d-")))
                }
                if (match != null) {
                    tts?.voice = match
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun resolveLocale(languageCode: String): Locale {
        val lang = languageCode.lowercase()
        return when {
            lang.contains("hindi") || lang == "hi" || lang == "hi-in" -> Locale("hi", "IN")
            lang.contains("telugu") || lang == "te" || lang == "te-in" -> Locale("te", "IN")
            lang.contains("tamil") || lang == "ta" || lang == "ta-in" -> Locale("ta", "IN")
            lang.contains("malayalam") || lang == "ml" || lang == "ml-in" -> Locale("ml", "IN")
            lang.contains("kannada") || lang == "kn" || lang == "kn-in" -> Locale("kn", "IN")
            else -> Locale("en", "IN")
        }
    }

    suspend fun speak(text: String, languageCode: String = "auto", personaId: String? = null): Boolean {
        if (personaId != null) {
            setVoicePersona(personaId)
        }
        val targetLocale = resolveLocale(languageCode)

        val isLangSupported = if (isInitialized && tts != null) {
            val availability = tts?.isLanguageAvailable(targetLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            availability >= TextToSpeech.LANG_AVAILABLE
        } else false

        if (isLangSupported) {
            val success = speakViaSystemTts(text, targetLocale)
            if (success) return true
        }

        return playLocalVerifiedSample(languageCode)
    }

    private suspend fun speakViaSystemTts(text: String, locale: Locale): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            tts?.language = locale
            applyPersona(activePersona)
            val utteranceId = "utterance_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun synthesizeToFile(text: String, outputFile: File, languageCode: String = "auto", personaId: String? = null): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(25000L) {
                suspendCancellableCoroutine { continuation ->
                    if (personaId != null) {
                        setVoicePersona(personaId)
                    }
                    val targetLocale = resolveLocale(languageCode)

                    try {
                        tts?.language = targetLocale
                        applyPersona(activePersona)
                        val utteranceId = "synth_${System.currentTimeMillis()}"
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                Log.d("TTSEngine", "synthesizeToFile onStart: $utteranceId")
                            }
                            override fun onDone(utteranceId: String?) {
                                Log.i("TTSEngine", "synthesizeToFile onDone: $utteranceId, file bytes=${outputFile.length()}")
                                if (continuation.isActive) continuation.resume(true)
                            }
                            override fun onError(utteranceId: String?) {
                                Log.e("TTSEngine", "synthesizeToFile onError: $utteranceId")
                                if (continuation.isActive) continuation.resume(false)
                            }
                        })
                        Log.i("TTSEngine", "Calling synthesizeToFile: text length=${text.length}, target=${outputFile.absolutePath}, utteranceId=$utteranceId")
                        val res = tts?.synthesizeToFile(text, null, outputFile, utteranceId)
                        Log.i("TTSEngine", "synthesizeToFile returned code: $res")
                        if (res != TextToSpeech.SUCCESS && continuation.isActive) {
                            continuation.resume(false)
                        }
                    } catch (e: Exception) {
                        Log.e("TTSEngine", "synthesizeToFile exception: ${e.message}", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: run {
                val hasFile = outputFile.exists() && outputFile.length() > 44
                Log.w("TTSEngine", "synthesizeToFile timed out after 25000ms; hasValidFile=$hasFile (${outputFile.length()}b)")
                hasFile
            }
        } catch (e: Exception) {
            Log.e("TTSEngine", "synthesizeToFile outer error: ${e.message}", e)
            false
        }
    }

    suspend fun synthesizeVoicePreview(personaId: String, languageCode: String = "en-IN"): Boolean {
        val sampleText = when {
            languageCode.contains("hindi", ignoreCase = true) || languageCode.startsWith("hi") -> "नमस्ते, एज रिसेप्शनिस्ट में आपका स्वागत है। मैं आपकी क्या सहायता कर सकता हूँ?"
            languageCode.contains("telugu", ignoreCase = true) || languageCode.startsWith("te") -> "నమస్కారం, ఎడ్జ్ రిసెప్షనిస్ట్‌కు స్వాగతం. నేను మీకు ఎలా సహాయపడగలను?"
            languageCode.contains("tamil", ignoreCase = true) || languageCode.startsWith("ta") -> "வணக்கம், எட்ஜ் வரவேற்பாளருக்கு வரவேற்கிறோம். நான் உங்களுக்கு எவ்வாறு உதவ முடியும்?"
            languageCode.contains("malayalam", ignoreCase = true) || languageCode.startsWith("ml") -> "നമസ്കാരം, എഡ്ജ് റിസപ്ഷനിസ്റ്റിലേക്ക് സ്വാഗതം. ഞാൻ നിങ്ങളെ എങ്ങനെ സഹായിക്കണം?"
            languageCode.contains("kannada", ignoreCase = true) || languageCode.startsWith("kn") -> "ನಮಸ್ಕಾರ, ಎಡ್ಜ್ ರಿಸೆಪ್ಷನಿಸ್ಟ್ ಗೆ ಸುಸ್ವಾಗತ. ನಾನು ನಿಮಗೆ ಹೇಗೆ ಸಹಾಯ ಮಾಡಲಿ?"
            else -> "Hello, thank you for calling Edge. I am your virtual receptionist. How may I assist you today?"
        }
        return speak(sampleText, languageCode, personaId)
    }

    suspend fun playLocalVerifiedSample(languageCode: String): Boolean = withContext(Dispatchers.IO) {
        val assetFilename = when {
            languageCode.contains("hindi", ignoreCase = true) || languageCode.equals("hi", ignoreCase = true) -> "hindi.wav"
            languageCode.contains("telugu", ignoreCase = true) || languageCode.equals("te", ignoreCase = true) -> "telugu.wav"
            languageCode.contains("tamil", ignoreCase = true) || languageCode.equals("ta", ignoreCase = true) -> "tamil.wav"
            languageCode.contains("malayalam", ignoreCase = true) || languageCode.equals("ml", ignoreCase = true) -> "malayalam.wav"
            else -> "english.wav"
        }

        suspendCancellableCoroutine { continuation ->
            try {
                val tempFile = File(context.cacheDir, "temp_tts_playback.wav")
                context.assets.open("audio_samples/$assetFilename").use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    setOnCompletionListener { mp ->
                        mp.release()
                        tempFile.delete()
                        if (continuation.isActive) continuation.resume(true)
                    }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        tempFile.delete()
                        if (continuation.isActive) continuation.resume(false)
                        true
                    }
                    start()
                }

                continuation.invokeOnCancellation {
                    try {
                        if (mediaPlayer.isPlaying) mediaPlayer.stop()
                        mediaPlayer.release()
                        tempFile.delete()
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
