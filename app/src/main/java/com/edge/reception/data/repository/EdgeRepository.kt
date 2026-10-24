package com.edge.reception.data.repository

import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.local.entity.BusinessProfileEntity
import com.edge.reception.data.local.entity.CallEntity
import com.edge.reception.data.local.entity.EntitlementEntity
import com.edge.reception.data.local.entity.TranscriptTurnEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class EdgeRepository(private val db: AppDatabase) {

    val allCalls: Flow<List<CallEntity>> = db.callDao().getAllCalls()
    val totalCallsCount: Flow<Int> = db.callDao().getTotalCount()
    val leadsCount: Flow<Int> = db.callDao().getLeadCount()
    val failsCount: Flow<Int> = db.callDao().getFailCount()
    val missedCount: Flow<Int> = db.callDao().getMissedCount()

    val businessProfile: Flow<BusinessProfileEntity?> = db.businessDao().getProfileFlow()
    val entitlement: Flow<EntitlementEntity?> = db.entitlementDao().getEntitlementFlow()

    fun getCallsFiltered(filter: String): Flow<List<CallEntity>> {
        return when (filter) {
            "LEADS" -> db.callDao().getCallsByOutcome("LEAD")
            "FAILS" -> db.callDao().getCallsByOutcome("FAIL")
            "MISSED" -> db.callDao().getCallsByOutcome("MISSED")
            else -> db.callDao().getAllCalls()
        }
    }

    fun getTranscriptForCall(callId: String): Flow<List<TranscriptTurnEntity>> {
        return db.transcriptDao().getTurnsForCall(callId)
    }

    suspend fun getCallById(callId: String): CallEntity? {
        return db.callDao().getCallById(callId)
    }

    suspend fun saveCall(call: CallEntity, turns: List<TranscriptTurnEntity>) {
        db.callDao().insertCall(call)
        db.transcriptDao().insertTurns(turns)
    }

    suspend fun updateAutoAnswer(enabled: Boolean) {
        val current = db.businessDao().getProfile() ?: BusinessProfileEntity()
        db.businessDao().saveProfile(current.copy(autoAnswerEnabled = enabled))
    }

    suspend fun updateBusinessProfile(name: String, type: String, language: String, delay: Int) {
        val current = db.businessDao().getProfile() ?: BusinessProfileEntity()
        db.businessDao().saveProfile(
            current.copy(
                businessName = name,
                businessType = type,
                defaultLanguage = language,
                autoAnswerDelaySeconds = delay
            )
        )
    }

    suspend fun initializeDefaultsIfNeeded() {
        if (db.businessDao().getProfile() == null) {
            db.businessDao().saveProfile(BusinessProfileEntity())
        }
        if (db.entitlementDao().getEntitlement() == null) {
            db.entitlementDao().saveEntitlement(EntitlementEntity())
        }
    }
}
