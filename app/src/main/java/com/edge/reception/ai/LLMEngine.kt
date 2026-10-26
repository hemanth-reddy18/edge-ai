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

        // 2. Medical Shop / Pharmacy Business Logic (Check specific domain first)
        if (businessType.contains("Medical", ignoreCase = true) || businessType.contains("Pharmacy", ignoreCase = true)) {
            if (lower.contains("fever") || lower.contains("paracetamol") || lower.contains("medicine") || lower.contains("pain")) {
                val reply = when {
                    language.startsWith("Hindi") -> "Haan ji, OTC medicines aur wellness items available hain. Kisi bhi prescription dava ke liye kripya doctor ki salah lein."
                    language.startsWith("Telugu") -> "General pharmacy items stock lo unnai sir. Prescription medicines kosam doctor consult cheyandi."
                    else -> "Yes, regular wellness supplies are available. For prescription medications, please consult a qualified healthcare provider or doctor."
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

        // 4. Interruption / Hold / Doubt handling
        if (lower.contains("wait") || lower.contains("hold on") || lower.contains("listen") || lower.contains("stop") || lower.contains("doubt") || lower.contains("one sec") || lower.contains("excuse me") || lower.contains("explain")) {
            val reply = "Yes, absolutely, I am listening! Please tell me your requirement or doubt."
            return TurnResponse(reply, "English", true)
        }

        // 5. Real Estate Business Logic (Bangalore & Multi-region Realty)
        if (businessType.equals("Real Estate", ignoreCase = true) || businessType.contains("Realty", ignoreCase = true) || lower.contains("flat") || lower.contains("land") || lower.contains("plot") || lower.contains("property") || lower.contains("bangalore") || lower.contains("sale") || lower.contains("realty")) {
            
            // 5a. Discounts & Bargaining / Price Negotiation
            if (lower.contains("discount") || lower.contains("bargain") || lower.contains("reduce") || lower.contains("cheaper") || lower.contains("less") || lower.contains("negotiate") || lower.contains("best price") || lower.contains("offer")) {
                val reply = "Our base rate is 8,500 rupees per square foot, but for serious buyers closing this week, I can arrange an exclusive pre-launch rebate and waive clubhouse charges. What offer did you have in mind?"
                return TurnResponse(reply, "English", true)
            }

            // 5b. Bangalore Land Prices & Rates per Sq Ft
            if (lower.contains("price") || lower.contains("rate") || lower.contains("cost") || lower.contains("per sq ft") || lower.contains("per square") || lower.contains("how much") || lower.contains("expensive") || lower.contains("budget")) {
                val reply = "In Whitefield and Sarjapur, gated developments range from 8,000 to 12,000 rupees per square foot, while North Bangalore near the Airport is 3,500 to 6,000. What budget range are you planning for?"
                return TurnResponse(reply, "English", true)
            }

            // 5c. Flats / Apartments Inventory (Supports Telugu and English inquiries)
            if (lower.contains("flat") || lower.contains("apartment") || lower.contains("3bhk") || lower.contains("2bhk") || lower.contains("4bhk") || lower.contains("duplex") || lower.contains("villa") || lower.contains("inventory") || lower.contains("house")) {
                if (language.startsWith("Telugu")) {
                    val reply = "Avunu sir, luxury 3BHK duplex flats available ga unnai, covered car parking facility undi. Meeru visit plan chestunnara?"
                    return TurnResponse(reply, language, true)
                }
                val reply = "We have luxury 2 and 3 BHK (3BHK) flats in Whitefield starting from 95 Lakhs, and 3 BHKs on Sarjapur Road around 1.65 Crores with parking. Are you looking to move in right away or looking for an investment?"
                return TurnResponse(reply, "English", true)
            }

            // 5d. How is the sale going / Market demand
            if (lower.contains("how is the sale") || lower.contains("sale going") || lower.contains("sales going") || lower.contains("how are sales") || lower.contains("market") || lower.contains("demand") || lower.contains("selling") || lower.contains("appreciation")) {
                val reply = "Sales are moving very fast! Phase 1 is over 85% sold out, with an 18% price appreciation in North Bangalore this year. Would you like me to reserve a priority unit for you?"
                return TurnResponse(reply, "English", true)
            }

            // 5e. Land & Plots Inventory
            if (lower.contains("plot") || lower.contains("plots") || lower.contains("land") || lower.contains("sites") || lower.contains("acre") || lower.contains("layout")) {
                val reply = "We have gated BDA and BMRDA villa plots near the International Airport starting from 1,200 square feet at 3,500 rupees per square foot. Are you planning to build immediately or hold for appreciation?"
                return TurnResponse(reply, "English", true)
            }

            // 5f. Site Visit & WhatsApp Brochure / Closing
            if (lower.contains("visit") || lower.contains("see") || lower.contains("brochure") || lower.contains("whatsapp") || lower.contains("details") || lower.contains("location") || lower.contains("meet") || lower.contains("call back")) {
                val reply = "I would be happy to host you for a VIP site visit this weekend with free doorstep cab pick-up! Shall I book a slot for you on Saturday morning?"
                return TurnResponse(reply, "English", true)
            }

            // 5g. General Real Estate Pitch Opener
            if (Regex("\\b(hi|hello|hey)\\b").containsMatchIn(lower)) {
                val reply = "Hello! Welcome to Edge Prime Realty Bangalore. We have luxury flats from 95 Lakhs and airport villa plots from 3,500 per square foot. How can I assist you today?"
                return TurnResponse(reply, "English", true)
            }

            val pitchReply = "We specialize in luxury apartments in Whitefield and high-growth villa plots near Bangalore Airport. Tell me, are you searching for a home for your family or a high-return investment?"
            return TurnResponse(pitchReply, "English", true)
        }

        // 6. Retail & Bargaining Conversational Logic
        if (Regex("\\b(hi|hello|hey|how are you)\\b").containsMatchIn(lower)) {
            if (lower.contains("jacket") || lower.contains("item") || lower.contains("price") || lower.contains("cost") || lower.contains("bargain") || lower.contains("discount") || lower.contains("expensive") || lower.contains("buy")) {
                val reply = "Hello! I am doing great, thank you for asking. Welcome to $businessName. The regular price for this item is 2,000 rupees, but as our special guest today, I can give you a 15% discount and offer it for 1,700 rupees. Does that work for you?"
                return TurnResponse(reply, "English", true)
            } else {
                val reply = "Hello! I am doing very well, thank you for asking. Welcome to $businessName. How can I assist you today?"
                return TurnResponse(reply, "English", true)
            }
        }

        if (lower.contains("1400") || lower.contains("1,400") || lower.contains("budget") || lower.contains("cheaper") || lower.contains("too high") || lower.contains("lower") || lower.contains("reduce") || lower.contains("more discount") || lower.contains("any better") || lower.contains("best price") || lower.contains("less")) {
            val reply = "I understand your budget! While 1,400 is below our wholesale cost, I want to make this work for you. The absolute best price I can do today is 1,500 rupees, and I will include free express shipping. Shall we lock this deal in?"
            return TurnResponse(reply, "English", true)
        }

        if (lower.contains("deal") || lower.contains("okay") || lower.contains("ok") || lower.contains("1500") || lower.contains("1,500") || lower.contains("fair") || lower.contains("take it") || lower.contains("confirm") || lower.contains("done") || lower.contains("sounds good")) {
            val reply = "Fantastic! That is a deal. I have locked in your order for 1,500 rupees with free express delivery. Thank you for shopping with $businessName, have a wonderful day!"
            return TurnResponse(reply, "English", true)
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
