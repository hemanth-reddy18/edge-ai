package com.edge.reception.telephony

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.edge.reception.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EdgeInCallService : InCallService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        if (call == null) return

        val state = call.state
        Log.i("EdgeInCallService", "Call added with state: $state")

        if (state == Call.STATE_RINGING) {
            handleIncomingCall(call)
        }
    }

    private fun handleIncomingCall(call: Call) {
        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val profile = db.businessDao().getProfile()
            val entitlement = db.entitlementDao().getEntitlement()

            // Auto-answer verification: enabled by default or per user switch
            val isAutoAnswerOn = profile?.autoAnswerEnabled != false
            val delaySeconds = profile?.autoAnswerDelaySeconds ?: 3

            if (isAutoAnswerOn) {
                Log.i("EdgeInCallService", "Incoming cellular call detected. Waiting $delaySeconds seconds before auto-answer...")
                delay(delaySeconds * 1000L)

                if (call.state == Call.STATE_RINGING) {
                    try {
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                        Log.i("EdgeInCallService", "Call answered successfully after $delaySeconds-second timer.")

                        // Set in-call audio mode
                        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = false

                        // Trigger Edge AI Real Estate Sales Pitch into call audio
                        val tts = com.edge.reception.ai.TTSEngine(applicationContext)
                        tts.init { ready ->
                            if (ready) {
                                val greeting = "Hello, thank you for calling Edge Prime Realty Bangalore. I am your senior property advisor. Are you inquiring about our luxury flats in Whitefield and Sarjapur, or our airport-corridor villa plots today?"
                                serviceScope.launch {
                                    tts.speak(greeting, "en-IN")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("EdgeInCallService", "Failed to answer call: ${e.message}")
                    }
                }
            } else {
                Log.i("EdgeInCallService", "Auto-answer disabled by user profile setting.")
            }
        }
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        Log.i("EdgeInCallService", "Call removed / disconnected.")
    }
}
