package com.edge.reception

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelManifestTest {

    @Test
    fun testManifestIntegrityAndLicensing() {
        val manifestFile = File("src/main/assets/models/manifest.json")
        assertTrue("Manifest file must exist", manifestFile.exists())

        val content = manifestFile.readText()
        val json = JSONObject(content)

        assertEquals("4.0.0", json.getString("version"))
        assertEquals(929, json.getInt("storage_required_mb"))

        val models = json.getJSONArray("models")
        assertEquals("Must include exactly 4 models (VAD, ASR, LLM, TTS)", 4, models.length())

        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            assertTrue("Filename must not be empty", m.getString("filename").isNotEmpty())
            assertTrue("SHA256 must be 64 hex chars", m.getString("sha256").length == 64)
            assertTrue("License must be specified", m.getString("license").isNotEmpty())
            assertTrue("Must specify supported languages", m.getJSONArray("languages").length() > 0)
        }
    }
}
