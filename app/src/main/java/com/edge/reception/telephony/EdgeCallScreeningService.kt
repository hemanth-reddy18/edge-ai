package com.edge.reception.telephony

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

class EdgeCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle
        Log.i("EdgeCallScreening", "Screening incoming call from: $handle")

        // Call screening allows identifying spam/robocalls or passing to normal ringing
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}
