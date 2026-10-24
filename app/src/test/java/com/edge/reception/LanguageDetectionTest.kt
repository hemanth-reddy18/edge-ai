package com.edge.reception

import com.edge.reception.ai.MultilingualASREngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LanguageDetectionTest {

    private lateinit var asrEngine: MultilingualASREngine

    @Before
    fun setUp() {
        asrEngine = MultilingualASREngine()
    }

    @Test
    fun testTeluguCodeMixedDetection() = runBlocking {
        val utterance = "Hello anna, Jubilee Hills lo 3BHK flat availability unda? Repu site visit cheyana?"
        val detected = asrEngine.detectLanguage(utterance)
        assertEquals("Telugu (Code-Mixed)", detected)
    }

    @Test
    fun testHindiDetection() = runBlocking {
        val utterance = "Kya ye office open hai? Mujhe pricing ke baare me jankari chahiye."
        val detected = asrEngine.detectLanguage(utterance)
        assertEquals("Hindi", detected)
    }

    @Test
    fun testTamilDetection() = runBlocking {
        val utterance = "Vanakkam sir, T-Nagar main road la showroom irukka? Details anuppuren."
        val detected = asrEngine.detectLanguage(utterance)
        assertEquals("Tamil", detected)
    }

    @Test
    fun testMalayalamDetection() = runBlocking {
        val utterance = "Chetta, 5kg rice stock undo? Home delivery cheyyamo?"
        val detected = asrEngine.detectLanguage(utterance)
        assertEquals("Malayalam", detected)
    }

    @Test
    fun testEnglishDetection() = runBlocking {
        val utterance = "Good morning, could you please provide details about the warranty policy?"
        val detected = asrEngine.detectLanguage(utterance)
        assertEquals("English", detected)
    }
}
