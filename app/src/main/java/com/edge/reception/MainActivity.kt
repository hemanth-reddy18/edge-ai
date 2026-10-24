package com.edge.reception

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.local.entity.CallEntity
import com.edge.reception.data.repository.EdgeRepository
import com.edge.reception.databinding.ActivityMainBinding
import com.edge.reception.ui.CallAdapter
import com.edge.reception.ui.CallDetailActivity
import com.edge.reception.ui.SettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: EdgeRepository
    private val callAdapter = CallAdapter { call ->
        openCallDetail(call)
    }

    private var currentFilter = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = EdgeRepository(db)

        setupRecyclerView()
        setupListeners()
        observeData()

        lifecycleScope.launch {
            repository.initializeDefaultsIfNeeded()
            repository.seedDemoCallsIfEmpty()
        }
    }

    private fun setupRecyclerView() {
        binding.rvCallHistory.layoutManager = LinearLayoutManager(this)
        binding.rvCallHistory.adapter = callAdapter
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.switchAutoAnswer.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                repository.updateAutoAnswer(isChecked)
                if (isChecked) {
                    binding.tvAutoAnswerDesc.text = getString(R.string.desc_auto_answer_active)
                    binding.tvAutoAnswerDesc.setTextColor(Color.parseColor("#38BDF8"))
                } else {
                    binding.tvAutoAnswerDesc.text = getString(R.string.desc_auto_answer_inactive)
                    binding.tvAutoAnswerDesc.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        binding.chipFilterAll.setOnClickListener { setFilter("ALL") }
        binding.chipFilterLeads.setOnClickListener { setFilter("LEADS") }
        binding.chipFilterFailed.setOnClickListener { setFilter("FAILS") }
    }

    private fun setFilter(filter: String) {
        currentFilter = filter

        binding.chipFilterAll.setBackgroundColor(if (filter == "ALL") Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
        binding.chipFilterAll.setTextColor(if (filter == "ALL") Color.parseColor("#020617") else Color.parseColor("#94A3B8"))

        binding.chipFilterLeads.setBackgroundColor(if (filter == "LEADS") Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
        binding.chipFilterLeads.setTextColor(if (filter == "LEADS") Color.parseColor("#020617") else Color.parseColor("#94A3B8"))

        binding.chipFilterFailed.setBackgroundColor(if (filter == "FAILS") Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
        binding.chipFilterFailed.setTextColor(if (filter == "FAILS") Color.parseColor("#020617") else Color.parseColor("#94A3B8"))

        observeCalls()
    }

    private fun observeData() {
        // Observe statistics
        lifecycleScope.launch {
            repository.totalCallsCount.collectLatest { count ->
                binding.tvCountTotal.text = count.toString()
            }
        }
        lifecycleScope.launch {
            repository.leadsCount.collectLatest { count ->
                binding.tvCountLeads.text = count.toString()
            }
        }
        lifecycleScope.launch {
            repository.failsCount.collectLatest { count ->
                binding.tvCountFailed.text = count.toString()
            }
        }
        lifecycleScope.launch {
            repository.missedCount.collectLatest { count ->
                binding.tvCountMissed.text = count.toString()
            }
        }

        // Observe business profile for auto-answer state
        lifecycleScope.launch {
            repository.businessProfile.collectLatest { profile ->
                if (profile != null) {
                    binding.switchAutoAnswer.isChecked = profile.autoAnswerEnabled
                    if (profile.autoAnswerEnabled) {
                        binding.tvAutoAnswerDesc.text = "Answering after ${profile.autoAnswerDelaySeconds}s delay"
                        binding.tvAutoAnswerDesc.setTextColor(Color.parseColor("#38BDF8"))
                    } else {
                        binding.tvAutoAnswerDesc.text = getString(R.string.desc_auto_answer_inactive)
                        binding.tvAutoAnswerDesc.setTextColor(Color.parseColor("#94A3B8"))
                    }
                }
            }
        }

        // Observe entitlement lease
        lifecycleScope.launch {
            repository.entitlement.collectLatest { entitlement ->
                if (entitlement != null && entitlement.isActive && entitlement.leaseExpiryTimestamp > System.currentTimeMillis()) {
                    binding.tvStatusBadge.text = getString(R.string.status_active)
                    binding.tvStatusBadge.setTextColor(Color.parseColor("#34D399"))
                } else {
                    binding.tvStatusBadge.text = getString(R.string.status_offline)
                    binding.tvStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
                }
            }
        }

        observeCalls()
    }

    private fun observeCalls() {
        lifecycleScope.launch {
            repository.getCallsFiltered(currentFilter).collectLatest { calls ->
                callAdapter.submitList(calls)
                binding.tvEmptyState.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openCallDetail(call: CallEntity) {
        val intent = Intent(this, CallDetailActivity::class.java).apply {
            putExtra("EXTRA_CALL_ID", call.id)
        }
        startActivity(intent)
    }
}
