package com.edge.reception.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.edge.reception.ai.TTSEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class TestAudioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.edge.reception.TEST_DYNAMIC_TTS") {
            val text = intent.getStringExtra("text") ?: "Edge AI Receptionist is running dynamic speech synthesis on device."
            val lang = intent.getStringExtra("lang") ?: "en"

            Log.i("TestAudioReceiver", "Received TEST_DYNAMIC_TTS request: text='$text', lang='$lang'")

            val pendingResult = goAsync()
            val ttsEngine = TTSEngine(context.applicationContext)
            ttsEngine.init { ready ->
                if (ready) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val outFile = File(context.filesDir, "dynamic_tts_output.wav")
                        val success = ttsEngine.synthesizeToFile(text, outFile, lang)
                        Log.i("TestAudioReceiver", "Synthesis completed: success=$success, path=${outFile.absolutePath}, bytes=${outFile.length()}")
                        ttsEngine.shutdown()
                        pendingResult.finish()
                    }
                } else {
                    Log.e("TestAudioReceiver", "TTSEngine initialization failed")
                    pendingResult.finish()
                }
            }
        }
    }
}
