package com.edge.reception.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.edge.reception.audio.LocalVoiceTestManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceTestReceiver : BroadcastReceiver() {

    companion object {
        var activeManager: LocalVoiceTestManager? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("VoiceTestReceiver", "Received broadcast action: $action")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "com.edge.reception.START_LOCAL_VOICE_TEST" -> {
                        val sessionId = intent.getStringExtra("session_id") ?: "test_session_adb_${System.currentTimeMillis()}"
                        val token = intent.getStringExtra("token") ?: "test_token"

                        Log.i("VoiceTestReceiver", "Starting local voice test session $sessionId with token $token")
                        if (activeManager?.isCurrentSession(sessionId) == true) {
                            Log.i("VoiceTestReceiver", "Session $sessionId is already active, ignoring duplicate broadcast")
                            return@launch
                        }
                        activeManager?.endSession()
                        val manager = LocalVoiceTestManager(context.applicationContext)
                        activeManager = manager
                        manager.startSession(sessionId, token) { state ->
                            Log.i("VoiceTestReceiver", "Session $sessionId state changed to: $state")
                        }
                    }
                    "com.edge.reception.END_LOCAL_VOICE_TEST" -> {
                        Log.i("VoiceTestReceiver", "Ending local voice test session")
                        activeManager?.endSession()
                        activeManager = null
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceTestReceiver", "Error in onReceive: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
