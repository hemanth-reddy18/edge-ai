package com.edge.reception.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class SubscriptionDetail(
    val subId: Int,
    val slotIndex: Int,
    val carrierName: String,
    val displayName: String,
    val countryIso: String,
    val rawPhoneNumber: String?,
    val isDefaultData: Boolean,
    val isDefaultVoice: Boolean
)

data class SimStatusResult(
    val totalCount: Int,
    val subscriptions: List<SubscriptionDetail>,
    val isNumberExposed: Boolean,
    val primaryNumber: String?,
    val requiresManualConfirmation: Boolean
)

object SimManager {

    private const val PREFS_NAME = "edge_sim_prefs"
    private const val KEY_CONFIRMED_NUMBER = "confirmed_sim_business_number"

    fun detectSubscriptions(context: Context): SimStatusResult {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasPhoneState) {
            val confirmed = getConfirmedBusinessNumber(context)
            return SimStatusResult(
                totalCount = 0,
                subscriptions = emptyList(),
                isNumberExposed = false,
                primaryNumber = confirmed,
                requiresManualConfirmation = confirmed.isNullOrEmpty()
            )
        }

        val subscriptions = mutableListOf<SubscriptionDetail>()
        var foundNumber: String? = null

        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val defaultDataId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultDataSubscriptionId()
            } else -1
            val defaultVoiceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultVoiceSubscriptionId()
            } else -1

            val list: List<SubscriptionInfo>? = sm?.activeSubscriptionInfoList
            if (!list.isNullOrEmpty()) {
                for (sub in list) {
                    var number: String? = null

                    if (hasPhoneNumbers) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val n = sm.getPhoneNumber(sub.subscriptionId)
                                if (!n.isNullOrBlank()) number = n.trim()
                            }
                        } catch (ignored: Exception) {}
                    }

                    if (number.isNullOrBlank()) {
                        try {
                            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                            val line1 = tm?.createForSubscriptionId(sub.subscriptionId)?.line1Number
                            if (!line1.isNullOrBlank()) number = line1.trim()
                        } catch (ignored: Exception) {}
                    }

                    if (!number.isNullOrBlank() && foundNumber == null) {
                        foundNumber = number
                    }

                    subscriptions.add(
                        SubscriptionDetail(
                            subId = sub.subscriptionId,
                            slotIndex = sub.simSlotIndex,
                            carrierName = sub.carrierName?.toString() ?: "Carrier",
                            displayName = sub.displayName?.toString() ?: "SIM ${sub.simSlotIndex + 1}",
                            countryIso = sub.countryIso ?: "IN",
                            rawPhoneNumber = number,
                            isDefaultData = (sub.subscriptionId == defaultDataId),
                            isDefaultVoice = (sub.subscriptionId == defaultVoiceId)
                        )
                    )
                }
            }
        } catch (ignored: Exception) {}

        val confirmedNumber = getConfirmedBusinessNumber(context)
        val finalNumber = if (!foundNumber.isNullOrBlank()) foundNumber else confirmedNumber
        val isExposed = !foundNumber.isNullOrBlank()

        return SimStatusResult(
            totalCount = subscriptions.size,
            subscriptions = subscriptions,
            isNumberExposed = isExposed,
            primaryNumber = finalNumber,
            requiresManualConfirmation = finalNumber.isNullOrBlank()
        )
    }

    fun getConfirmedBusinessNumber(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CONFIRMED_NUMBER, null)
    }

    fun saveConfirmedBusinessNumber(context: Context, number: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIRMED_NUMBER, number.trim()).apply()
    }
}
