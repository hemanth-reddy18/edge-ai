package com.edge.reception

import com.edge.reception.ai.LocalLLMEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LLMEngineTest {

    private lateinit var llm: LocalLLMEngine

    @Before
    fun setUp() {
        llm = LocalLLMEngine()
    }

    @Test
    fun testRealEstate3BHKInquiryGeneratesLead() = runBlocking {
        val history = listOf(
            "EDGE" to "Hello, you've reached Edge Realty. How may I help you?",
            "CUSTOMER" to "Hello anna, Jubilee Hills lo 3BHK duplex flat availability unda? 2-car parking kavali."
        )

        val reply = llm.generateReply(
            businessType = "Real Estate",
            businessName = "Edge Realty",
            dialogueHistory = history,
            latestCustomerSpeech = history.last().second,
            language = "Telugu (Code-Mixed)"
        )

        assertTrue("Reply should address 3BHK and parking", reply.replyText.contains("3BHK") || reply.replyText.contains("parking"))
        assertTrue("Should require follow-up action", reply.requiresAction)

        val analysis = llm.analyzeCall("Real Estate", history)
        assertTrue("Should be marked as LEAD", analysis.isLead)
        assertEquals("LEAD", analysis.outcome)
        assertTrue("Summary should mention 3BHK", analysis.summary.contains("3BHK"))
    }

    @Test
    fun testWrongNumberInquiryGeneratesFail() = runBlocking {
        val history = listOf(
            "EDGE" to "Hello, you've reached Edge Realty. How may I help you?",
            "CUSTOMER" to "Kya ye SBI bank branch hai? Mujhe passbook print karwana hai."
        )

        val reply = llm.generateReply(
            businessType = "Real Estate",
            businessName = "Edge Realty",
            dialogueHistory = history,
            latestCustomerSpeech = history.last().second,
            language = "Hindi"
        )

        assertTrue("Should disclaim wrong number", reply.replyText.contains("galat number") || reply.replyText.contains("Edge Realty"))
        assertFalse("No sales action needed", reply.requiresAction)

        val analysis = llm.analyzeCall("Real Estate", history)
        assertFalse("Wrong number must NOT be a lead", analysis.isLead)
        assertEquals("FAIL", analysis.outcome)
        assertTrue("Summary should note wrong number", analysis.summary.contains("wrong number"))
    }

    @Test
    fun testSupermarketDeliveryInquiry() = runBlocking {
        val history = listOf(
            "EDGE" to "Hello, you've reached Edge Supermarket.",
            "CUSTOMER" to "Chetta, 5kg rice home delivery cheyyamo?"
        )

        val reply = llm.generateReply(
            businessType = "Supermarket",
            businessName = "Edge Supermarket",
            dialogueHistory = history,
            latestCustomerSpeech = history.last().second,
            language = "Malayalam"
        )

        assertTrue("Should confirm rice stock in Malayalam", reply.replyText.contains("rice") || reply.replyText.contains("delivery"))
    }

    @Test
    fun testPharmacyStrictMedicalDisclaimer() = runBlocking {
        val history = listOf(
            "EDGE" to "Hello, you've reached Edge Pharmacy.",
            "CUSTOMER" to "Mujhe high fever hai, kaunsi medicine leni chahiye?"
        )

        val reply = llm.generateReply(
            businessType = "Medical Shop / Pharmacy",
            businessName = "Edge Pharmacy",
            dialogueHistory = history,
            latestCustomerSpeech = history.last().second,
            language = "Hindi"
        )

        assertTrue("Pharmacy must advise consulting doctor", reply.replyText.contains("doctor") || reply.replyText.contains("prescription"))
    }
}
