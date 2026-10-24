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

            // Verification gates
            val isAutoAnswerOn = profile?.autoAnswerEnabled == true
            val hasActiveLease = entitlement?.isActive == true && (entitlement.leaseExpiryTimestamp > System.currentTimeMillis())
            val killSwitchActive = entitlement?.killSwitchActive == true
            val delaySeconds = profile?.autoAnswerDelaySeconds ?: 3

            if (isAutoAnswerOn && hasActiveLease && !killSwitchActive) {
                Log.i("EdgeInCallService", "Pre-conditions met. Waiting $delaySeconds seconds before auto-answer...")
                delay(delaySeconds * 1000L)

                if (call.state == Call.STATE_RINGING) {
                    try {
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                        Log.i("EdgeInCallService", "Call answered successfully.")
                    } catch (e: Exception) {
                        Log.e("EdgeInCallService", "Failed to answer call: ${e.message}")
                    }
                }
            } else {
                Log.i("EdgeInCallService", "Auto-answer declined: autoAnswer=$isAutoAnswerOn, activeLease=$hasActiveLease, killSwitch=$killSwitchActive")
            }
        }
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        Log.i("EdgeInCallService", "Call removed / disconnected.")
    }
}
