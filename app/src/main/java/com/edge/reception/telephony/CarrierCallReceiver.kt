package com.edge.reception.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CarrierCallReceiver : BroadcastReceiver() {

    companion object {
        var activeBridge: CarrierCallBridge? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("CarrierCallReceiver", "Received broadcast action: $action")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "com.edge.reception.START_CARRIER_CALL" -> {
                        val callId = intent.getStringExtra("call_id") ?: "sip_call_${System.currentTimeMillis()}"
                        val token = intent.getStringExtra("token") ?: "sip_token"
                        val isTestCall = intent.getBooleanExtra("is_test_call", true)

                        Log.i("CarrierCallReceiver", "Starting carrier call session $callId with token $token (isTest=$isTestCall)")
                        val bridge = CarrierCallBridge(context.applicationContext)
                        activeBridge = bridge
                        bridge.startCall(callId, token, isTestCall = isTestCall) { state ->
                            Log.i("CarrierCallReceiver", "Call $callId state changed to: $state")
                        }
                    }
                    "com.edge.reception.END_CARRIER_CALL" -> {
                        Log.i("CarrierCallReceiver", "Ending carrier call session")
                        activeBridge?.endCall()
                        activeBridge = null
                    }
                }
            } catch (e: Exception) {
                Log.e("CarrierCallReceiver", "Error in onReceive: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
