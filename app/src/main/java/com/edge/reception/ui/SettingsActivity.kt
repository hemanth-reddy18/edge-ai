package com.edge.reception.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.repository.EdgeRepository
import com.edge.reception.databinding.ActivitySettingsBinding
import com.edge.reception.model.ModelManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: EdgeRepository
    private lateinit var modelManager: ModelManager

    private val businessTypes = arrayOf(
        "Real Estate",
        "Supermarket",
        "Medical Shop / Pharmacy",
        "Custom Customer Support"
    )

    private val languages = arrayOf(
        "English",
        "Hindi",
        "Telugu",
        "Tamil",
        "Malayalam"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = EdgeRepository(db)
        modelManager = ModelManager(this)

        setupSpinners()
        setupListeners()
        loadCurrentSettings()
    }

    private fun setupSpinners() {
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, businessTypes)
        binding.spinnerBusinessType.adapter = typeAdapter

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

        binding.btnSaveSettings.setOnClickListener {
            val name = binding.etBusinessName.text.toString().trim()
            val type = binding.spinnerBusinessType.selectedItem.toString()
            val language = binding.spinnerLanguage.selectedItem.toString()
            val delay = binding.etAnswerDelay.text.toString().toIntOrNull() ?: 3

            lifecycleScope.launch {
                repository.updateBusinessProfile(name, type, language, delay)
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
            }

            val progress = modelManager.getDownloadProgressPercent()
            binding.pbModelDownload.progress = progress
            if (modelManager.areAllModelsReady()) {
                binding.tvModelStatus.text = "Status: All on-device AI models ready (510 MB)"
            } else {
                binding.tvModelStatus.text = "Status: Preparing Edge (${progress}% downloaded, approx 510 MB)"
            }
        }
    }
}
