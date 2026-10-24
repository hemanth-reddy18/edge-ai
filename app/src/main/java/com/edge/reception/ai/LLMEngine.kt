package com.edge.reception.ai

data class TurnResponse(
    val replyText: String,
    val replyLanguage: String,
    val requiresAction: Boolean
)

data class CallAnalysis(
    val isLead: Boolean,
    val outcome: String, // "LEAD" or "FAIL"
    val summary: String,
    val intent: String,
    val nextAction: String
)

interface LLMEngine {
    suspend fun generateReply(
        businessType: String,
        businessName: String,
        dialogueHistory: List<Pair<String, String>>, // Speaker to Text
        latestCustomerSpeech: String,
        language: String
    ): TurnResponse

    suspend fun analyzeCall(
        businessType: String,
        dialogueHistory: List<Pair<String, String>>
    ): CallAnalysis
}

class LocalLLMEngine : LLMEngine {

    override suspend fun generateReply(
        businessType: String,
        businessName: String,
        dialogueHistory: List<Pair<String, String>>,
        latestCustomerSpeech: String,
        language: String
    ): TurnResponse {
        val lower = latestCustomerSpeech.lowercase()

        // 1. Wrong number check
        if (lower.contains("wrong number") || lower.contains("bank branch") || lower.contains("passbook") || lower.contains("pizza")) {
            val reply = when {
                language.startsWith("Hindi") -> "Nahi sir, ye $businessName hai. Ye galat number hai."
                language.startsWith("Telugu") -> "Ledu sir, idi $businessName office. Idi wrong number."
                language.startsWith("Tamil") -> "Illa sir, idhu $businessName. Wrong number."
                else -> "No sir, this is $businessName. You have reached a wrong number."
            }
            return TurnResponse(reply, language, false)
        }

        // 2. Real Estate Business Logic
        if (businessType.equals("Real Estate", ignoreCase = true)) {
            if (lower.contains("3bhk") || lower.contains("duplex") || lower.contains("flat") || lower.contains("villa")) {
                val reply = when {
                    language.startsWith("Telugu") -> "Yes sir, Jubilee Hills Road 36 lo premium 3BHK duplex ready-to-move undi with covered double parking. Repu 4 PM site visit book cheyana?"
                    language.startsWith("Hindi") -> "Ji sir, Jubilee Hills me ready-to-move 3BHK duplex flat available hai with parking. Kya kal 4 baje site visit schedule karein?"
                    language.startsWith("Tamil") -> "Aama sir, prime location la 3BHK duplex ready-to-move flat irukku with parking. Naalaiki site visit book pannalama?"
                    else -> "Yes sir, we have ready-to-move premium 3BHK apartments with dedicated parking. Would you like to schedule a site visit tomorrow at 4 PM?"
                }
                return TurnResponse(reply, language, true)
            }
            if (lower.contains("showroom") || lower.contains("commercial") || lower.contains("office")) {
                val reply = when {
                    language.startsWith("Tamil") -> "Kandippa irukku sir. Prime commercial showroom available. Details WhatsApp anuppuren."
                    language.startsWith("Telugu") -> "Commercial space available undi sir. Details WhatsApp chestamu."
                    else -> "Yes, prime commercial space is available. I will send complete brochure details to your WhatsApp."
                }
                return TurnResponse(reply, language, true)
            }
        }

        // 3. Supermarket Business Logic
        if (businessType.equals("Supermarket", ignoreCase = true)) {
            if (lower.contains("rice") || lower.contains("oil") || lower.contains("delivery") || lower.contains("sugar")) {
                val reply = when {
                    language.startsWith("Malayalam") -> "Undu chetta, 5kg premium rice stock undu. 1 houril home delivery cheyyaam."
                    language.startsWith("Hindi") -> "Haan ji, 5kg rice stock me hai aur 1 hour me home delivery ho jayegi."
                    language.startsWith("Telugu") -> "Rice stock undi sir. 1 hour lo home delivery chestamu."
                    else -> "Yes, we have 5kg rice in stock. We can arrange home delivery within one hour."
                }
                return TurnResponse(reply, language, true)
            }
        }

        // 4. Medical Shop / Pharmacy Business Logic
        if (businessType.contains("Medical", ignoreCase = true) || businessType.contains("Pharmacy", ignoreCase = true)) {
            if (lower.contains("fever") || lower.contains("paracetamol") || lower.contains("medicine") || lower.contains("pain")) {
                val reply = when {
                    language.startsWith("Hindi") -> "Haan ji, OTC medicines aur wellness items available hain. Kisi bhi prescription dava ke liye kripya doctor ki salah lein."
                    language.startsWith("Telugu") -> "General pharmacy items stock lo unnai sir. Prescription medicines kosam doctor consult cheyandi."
                    else -> "Yes, regular wellness supplies are available. For prescription medications, please consult a qualified healthcare provider."
                }
                return TurnResponse(reply, language, true)
            }
        }

        // General fallback
        val defaultReply = when {
            language.startsWith("Telugu") -> "Chala thanks sir. Mee requirement note chesukunaanu. Ma team ventane call back chestundi."
            language.startsWith("Hindi") -> "Dhanyavad sir. Aapki inquiry record ho gayi hai. Hamari team jaldi contact karegi."
            language.startsWith("Tamil") -> "Romba nandri sir. Unga details note panniten. Enga team call back pannuvaanga."
            language.startsWith("Malayalam") -> "Nanni chetta. Details note cheythu. Team udan contact cheyyum."
            else -> "Thank you. I have recorded your inquiry and our team will get in touch with you shortly."
        }
        return TurnResponse(defaultReply, language, false)
    }

    override suspend fun analyzeCall(
        businessType: String,
        dialogueHistory: List<Pair<String, String>>
    ): CallAnalysis {
        val fullText = dialogueHistory.joinToString(" ") { it.second }.lowercase()

        val isFail = fullText.contains("wrong number") ||
                fullText.contains("galat number") ||
                fullText.contains("passbook") ||
                fullText.contains("bank branch") ||
                fullText.contains("spam") ||
                fullText.contains("not interested")

        if (isFail) {
            return CallAnalysis(
                isLead = false,
                outcome = "FAIL",
                summary = "Caller had a wrong number inquiry or unrelated service request. No business opportunity.",
                intent = "Wrong Number / Unrelated Inquiry",
                nextAction = "No follow-up needed."
            )
        }

        // Business Lead
        val intent = when {
            fullText.contains("3bhk") || fullText.contains("flat") || fullText.contains("villa") -> "Property Purchase / Site Visit Inquiry"
            fullText.contains("showroom") || fullText.contains("commercial") -> "Commercial Space Lease Inquiry"
            fullText.contains("rice") || fullText.contains("delivery") -> "Grocery Order & Delivery Request"
            fullText.contains("paracetamol") || fullText.contains("medicine") -> "Pharmacy Stock & Availability Check"
            else -> "General Business Inquiry"
        }

        val summary = when {
            fullText.contains("3bhk") -> "Caller inquired about ready-to-move 3BHK flat availability with parking. Scheduled site visit and requested WhatsApp brochure."
            fullText.contains("showroom") -> "Caller requested commercial showroom rental details on main road. Budget and contact details recorded for sales team."
            fullText.contains("delivery") -> "Customer placed inquiry for grocery items with immediate home delivery requirement."
            else -> "Customer called regarding product availability and requested pricing and follow-up from sales."
        }

        val nextAction = when {
            fullText.contains("3bhk") -> "Send location on WhatsApp and confirm 4 PM site visit."
            fullText.contains("showroom") -> "Sales executive to call back with commercial lease terms."
            else -> "Dispatch requested catalog and assign to customer support representative."
        }

        return CallAnalysis(
            isLead = true,
            outcome = "LEAD",
            summary = summary,
            intent = intent,
            nextAction = nextAction
        )
    }
}
