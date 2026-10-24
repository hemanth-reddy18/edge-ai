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

    suspend fun seedDemoCallsIfEmpty() {
        val currentProfile = db.businessDao().getProfile()
        if (currentProfile == null) {
            db.businessDao().saveProfile(BusinessProfileEntity())
        }

        // Add verified demo calls showcasing multilingual support and Lead/Fail classification
        val demoCall1Id = "demo_call_001"
        val demoCall1 = CallEntity(
            id = demoCall1Id,
            callerId = "+91 98480 22338",
            timestamp = System.currentTimeMillis() - 15 * 60 * 1000,
            durationSeconds = 62,
            language = "Telugu (Code-Mixed)",
            isLead = true,
            outcome = "LEAD",
            summary = "Caller requested 3BHK duplex details in Jubilee Hills with 2-car parking. Scheduled site visit for tomorrow 4 PM.",
            audioRecordingPath = null,
            isDemo = true,
            synced = true
        )

        val demoCall1Turns = listOf(
            TranscriptTurnEntity(
                callId = demoCall1Id,
                speaker = "EDGE",
                recognizedText = "Hello, you've reached Edge Realty. I'm the virtual receptionist. How may I help you today?",
                englishTranslation = "Hello, you've reached Edge Realty. I'm the virtual receptionist. How may I help you today?",
                detectedLanguage = "English",
                timestampMs = 0,
                confidence = 0.99f
            ),
            TranscriptTurnEntity(
                callId = demoCall1Id,
                speaker = "CUSTOMER",
                recognizedText = "Hello anna, Jubilee Hills lo 3BHK flat availability unda? 2-car parking compulsory kavali.",
                englishTranslation = "Hello brother, is 3BHK flat available in Jubilee Hills? 2-car parking is mandatory.",
                detectedLanguage = "Telugu (Code-Mixed)",
                timestampMs = 3500,
                confidence = 0.96f
            ),
            TranscriptTurnEntity(
                callId = demoCall1Id,
                speaker = "EDGE",
                recognizedText = "Yes sir, Jubilee Hills Road 36 lo premium 3BHK duplex ready-to-move undi with covered double parking. Repu 4 PM site visit book cheyana?",
                englishTranslation = "Yes sir, in Jubilee Hills Road 36 we have a ready-to-move premium 3BHK duplex with covered double parking. Shall I book a site visit tomorrow at 4 PM?",
                detectedLanguage = "Telugu (Code-Mixed)",
                timestampMs = 12000,
                confidence = 0.97f
            ),
            TranscriptTurnEntity(
                callId = demoCall1Id,
                speaker = "CUSTOMER",
                recognizedText = "Haan book cheyandi. Location WhatsApp cheyandi.",
                englishTranslation = "Yes please book it. Send the location on WhatsApp.",
                detectedLanguage = "Telugu (Code-Mixed)",
                timestampMs = 21000,
                confidence = 0.98f
            )
        )

        val demoCall2Id = "demo_call_002"
        val demoCall2 = CallEntity(
            id = demoCall2Id,
            callerId = "+91 99001 54321",
            timestamp = System.currentTimeMillis() - 75 * 60 * 1000,
            durationSeconds = 24,
            language = "Hindi",
            isLead = false,
            outcome = "FAIL",
            summary = "Wrong number inquiry looking for a personal bank branch. No real estate interest.",
            audioRecordingPath = null,
            isDemo = true,
            synced = true
        )

        val demoCall2Turns = listOf(
            TranscriptTurnEntity(
                callId = demoCall2Id,
                speaker = "EDGE",
                recognizedText = "Hello, you've reached Edge Realty. How may I help you?",
                englishTranslation = "Hello, you've reached Edge Realty. How may I help you?",
                detectedLanguage = "English",
                timestampMs = 0,
                confidence = 0.99f
            ),
            TranscriptTurnEntity(
                callId = demoCall2Id,
                speaker = "CUSTOMER",
                recognizedText = "Kya ye SBI bank branch hai? Mujhe passbook print karwana hai.",
                englishTranslation = "Is this SBI bank branch? I need to get my passbook printed.",
                detectedLanguage = "Hindi",
                timestampMs = 3000,
                confidence = 0.95f
            ),
            TranscriptTurnEntity(
                callId = demoCall2Id,
                speaker = "EDGE",
                recognizedText = "Nahi sir, ye Edge Realty property office hai. Ye galat number hai.",
                englishTranslation = "No sir, this is Edge Realty property office. This is a wrong number.",
                detectedLanguage = "Hindi",
                timestampMs = 9000,
                confidence = 0.98f
            )
        )

        val demoCall3Id = "demo_call_003"
        val demoCall3 = CallEntity(
            id = demoCall3Id,
            callerId = "+91 97402 11889",
            timestamp = System.currentTimeMillis() - 180 * 60 * 1000,
            durationSeconds = 48,
            language = "Tamil",
            isLead = true,
            outcome = "LEAD",
            summary = "Inquiry regarding commercial showroom space in T-Nagar. Budget 1.5 Lakhs/month. Contact details recorded.",
            audioRecordingPath = null,
            isDemo = true,
            synced = true
        )

        val demoCall3Turns = listOf(
            TranscriptTurnEntity(
                callId = demoCall3Id,
                speaker = "EDGE",
                recognizedText = "Vanakkam, Edge Realty. How may I help you today?",
                englishTranslation = "Welcome, Edge Realty. How may I help you today?",
                detectedLanguage = "Tamil / English",
                timestampMs = 0,
                confidence = 0.99f
            ),
            TranscriptTurnEntity(
                callId = demoCall3Id,
                speaker = "CUSTOMER",
                recognizedText = "Vanakkam, T-Nagar main road la 1200 sqft commercial showroom space irukka? Budget around 1.5 Lakhs.",
                englishTranslation = "Greetings, is there a 1200 sqft commercial showroom space on T-Nagar main road? Budget around 1.5 Lakhs.",
                detectedLanguage = "Tamil",
                timestampMs = 4000,
                confidence = 0.96f
            ),
            TranscriptTurnEntity(
                callId = demoCall3Id,
                speaker = "EDGE",
                recognizedText = "Kandippa irukku sir. Ground floor showroom with prime frontage available. Ungalukku brochure WhatsApp anuppuren.",
                englishTranslation = "Certainly available sir. Ground floor showroom with prime frontage available. I will send you the brochure on WhatsApp.",
                detectedLanguage = "Tamil",
                timestampMs = 14000,
                confidence = 0.97f
            )
        )

        saveCall(demoCall1, demoCall1Turns)
        saveCall(demoCall2, demoCall2Turns)
        saveCall(demoCall3, demoCall3Turns)
    }
}
