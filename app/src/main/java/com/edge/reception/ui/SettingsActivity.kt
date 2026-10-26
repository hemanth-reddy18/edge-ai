package com.edge.reception.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.edge.reception.ai.TTSEngine
import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.repository.EdgeRepository
import com.edge.reception.databinding.ActivitySettingsBinding
import com.edge.reception.model.ModelManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: EdgeRepository
    private lateinit var modelManager: ModelManager
    private var ttsEngine: TTSEngine? = null

    private val businessTypes = arrayOf(
        "Real Estate",
        "Supermarket",
        "Medical Shop / Pharmacy",
        "Customer Support",
        "Custom Business"
    )

    private val voicePersonas = arrayOf(
        "Female (South Indian)",
        "Male (South Indian)"
    )

    private val languages = arrayOf(
        "Auto-Detect (Multilingual)",
        "English",
        "Hindi",
        "Telugu",
        "Tamil",
        "Malayalam",
        "Kannada"
    )

    private var scriptTemplates = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = EdgeRepository(db)
        modelManager = ModelManager(this)
        ttsEngine = TTSEngine(this).apply {
            init { /* ready */ }
        }

        loadScriptTemplates()
        setupSpinners()
        setupListeners()
        loadCurrentSettings()
    }

    private fun loadScriptTemplates() {
        try {
            val jsonStr = assets.open("business_scripts.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val templates = root.getJSONArray("templates")
            for (i in 0 until templates.length()) {
                val item = templates.getJSONObject(i)
                val bType = item.getString("business_type")
                val greeting = item.getString("greeting_template")
                scriptTemplates[bType] = greeting
            }
        } catch (e: Exception) {
            scriptTemplates["Real Estate"] = "Hello, thank you for contacting Edge Realty. How may I help you today?"
        }
    }

    private fun setupSpinners() {
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, businessTypes)
        binding.spinnerBusinessType.adapter = typeAdapter

        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voicePersonas)
        binding.spinnerVoicePersona.adapter = voiceAdapter

        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        binding.spinnerLanguage.adapter = langAdapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnNotices.setOnClickListener {
            startActivity(Intent(this, NoticesActivity::class.java))
        }

        binding.spinnerBusinessType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedType = businessTypes[position]
                val bName = binding.etBusinessName.text.toString().ifEmpty { "Your Business" }
                val template = scriptTemplates[selectedType] ?: "Hello, thank you for calling {business_name}. How may I help you today?"
                binding.tvScriptGreetingPreview.text = template.replace("{business_name}", bName)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnPreviewVoice.setOnClickListener {
            val isFemale = binding.spinnerVoicePersona.selectedItemPosition == 0
            val personaId = if (isFemale) TTSEngine.PERSONA_FEMALE else TTSEngine.PERSONA_MALE
            val selectedLang = binding.spinnerLanguage.selectedItem.toString()

            binding.btnPreviewVoice.isEnabled = false
            binding.btnPreviewVoice.text = "Playing preview..."
            lifecycleScope.launch {
                ttsEngine?.synthesizeVoicePreview(personaId, selectedLang)
                binding.btnPreviewVoice.isEnabled = true
                binding.btnPreviewVoice.text = "Preview Selected Voice"
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            val name = binding.etBusinessName.text.toString().trim()
            val type = binding.spinnerBusinessType.selectedItem.toString()
            val language = binding.spinnerLanguage.selectedItem.toString()
            val delay = binding.etAnswerDelay.text.toString().toIntOrNull() ?: 3
            val persona = if (binding.spinnerVoicePersona.selectedItemPosition == 0) TTSEngine.PERSONA_FEMALE else TTSEngine.PERSONA_MALE
            val simNum = binding.etSimNumber.text.toString().trim()
            val fwdDest = binding.etForwardingDestination.text.toString().trim()

            if (simNum.isNotEmpty()) {
                com.edge.reception.telephony.SimManager.saveConfirmedBusinessNumber(this@SettingsActivity, simNum)
            }
            if (fwdDest.isNotEmpty()) {
                getSharedPreferences("edge_sim_prefs", MODE_PRIVATE).edit().putString("gateway_fwd_destination", fwdDest).apply()
                if (fwdDest == "1000") {
                    Toast.makeText(this@SettingsActivity, "Note: 1000 is a local lab PBX extension. Public cellular forwarding requires an E.164 number (+91...).", Toast.LENGTH_LONG).show()
                }
            }

            lifecycleScope.launch {
                repository.updateBusinessProfile(name, type, language, delay, persona, "1.0.0")
                Toast.makeText(this@SettingsActivity, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadCurrentSettings() {
        lifecycleScope.launch {
            val profile = repository.businessProfile.firstOrNull()
            if (profile != null) {
                binding.etBusinessName.setText(profile.businessName)
                binding.etAnswerDelay.setText(profile.autoAnswerDelaySeconds.toString())

                val typeIdx = businessTypes.indexOf(profile.businessType)
                if (typeIdx >= 0) binding.spinnerBusinessType.setSelection(typeIdx)

                val langIdx = languages.indexOf(profile.defaultLanguage)
                if (langIdx >= 0) binding.spinnerLanguage.setSelection(langIdx)

                val personaIdx = if (profile.voicePersona == TTSEngine.PERSONA_MALE) 1 else 0
                binding.spinnerVoicePersona.setSelection(personaIdx)

                val template = scriptTemplates[profile.businessType] ?: scriptTemplates["Real Estate"] ?: ""
                binding.tvScriptGreetingPreview.text = template.replace("{business_name}", profile.businessName)
            }

            val simStatus = com.edge.reception.telephony.SimManager.detectSubscriptions(this@SettingsActivity)
            val currentSimNumber = simStatus.primaryNumber ?: ""
            binding.etSimNumber.setText(currentSimNumber)

            val fwdDest = getSharedPreferences("edge_sim_prefs", MODE_PRIVATE).getString("gateway_fwd_destination", "")
            binding.etForwardingDestination.setText(fwdDest)

            val progress = modelManager.getDownloadProgressPercent()
            binding.pbModelDownload.progress = progress
            if (modelManager.areAllModelsReady()) {
                binding.tvModelStatus.text = "Status: All on-device AI models ready (491 MB)"
            } else {
                binding.tvModelStatus.text = "Status: Downloading AI models ($progress%)"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine?.shutdown()
    }
}
