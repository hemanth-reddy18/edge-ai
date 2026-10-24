package com.edge.reception.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class TTSEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    fun init(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status ->
            isInitialized = (status == TextToSpeech.SUCCESS)
            if (isInitialized) {
                tts?.language = Locale.ENGLISH
            }
            onReady(isInitialized)
        }
    }

    suspend fun speak(text: String, languageCode: String = "en"): Boolean = suspendCancellableCoroutine { continuation ->
        if (!isInitialized || tts == null) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val targetLocale = when (languageCode.lowercase()) {
            "hindi", "hi" -> Locale("hi", "IN")
            "telugu", "te" -> Locale("te", "IN")
            "tamil", "ta" -> Locale("ta", "IN")
            "malayalam", "ml" -> Locale("ml", "IN")
            else -> Locale.ENGLISH
        }

        try {
            tts?.language = targetLocale
        } catch (e: Exception) {
            tts?.language = Locale.ENGLISH
        }

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
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
