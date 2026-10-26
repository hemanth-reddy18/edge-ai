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

    private val voiceTestReceiver = com.edge.reception.telephony.VoiceTestReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val db = AppDatabase.getInstance(this)
        repository = EdgeRepository(db)

        val filter = android.content.IntentFilter().apply {
            addAction("com.edge.reception.START_LOCAL_VOICE_TEST")
            addAction("com.edge.reception.END_LOCAL_VOICE_TEST")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, voiceTestReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        handleVoiceTestIntent(intent)

        setupRecyclerView()
        setupListeners()
        observeData()
        updateTelephonyAndSimStatus()
        setupTelephonyAutoAnswerListener()

        lifecycleScope.launch {
            repository.initializeDefaultsIfNeeded()
            // Clean up any demo calls to ensure only real calls exist
            db.callDao().deleteDemoCalls()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleVoiceTestIntent(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(voiceTestReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleVoiceTestIntent(intent: Intent) {
        val sessionId = intent.getStringExtra("session_id")
        val token = intent.getStringExtra("token")
        if (!sessionId.isNullOrEmpty() && !token.isNullOrEmpty()) {
            android.util.Log.i("MainActivity", "Starting local voice test session via Activity Intent: $sessionId")
            val current = com.edge.reception.telephony.VoiceTestReceiver.activeManager
            if (current?.isCurrentSession(sessionId) == true) {
                android.util.Log.i("MainActivity", "Session $sessionId is already active, ignoring duplicate intent")
                return
            }
            current?.endSession()
            val manager = com.edge.reception.audio.LocalVoiceTestManager(applicationContext)
            com.edge.reception.telephony.VoiceTestReceiver.activeManager = manager
            manager.startSession(sessionId, token)
        }
    }

    override fun onResume() {
        super.onResume()
        updateTelephonyAndSimStatus()
    }

    private fun updateTelephonyAndSimStatus() {
        val simStatus = com.edge.reception.telephony.SimManager.detectSubscriptions(this)
        val audioAudit = com.edge.reception.telephony.CellularAudioCapabilityTester.auditDeviceCapability(this)

        if (simStatus.subscriptions.isNotEmpty()) {
            val primary = simStatus.subscriptions.firstOrNull { it.isDefaultVoice } ?: simStatus.subscriptions[0]
            val numberStr = if (!simStatus.primaryNumber.isNullOrEmpty()) " (${simStatus.primaryNumber})" else " (Tap dialer to confirm #)"
            binding.tvSimDetail.text = "Slot ${primary.slotIndex + 1}: ${primary.carrierName} [${primary.displayName}]$numberStr"
            binding.tvSimBadge.text = "SIM Detected"
            binding.tvSimBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#064E3B"))
            binding.tvSimBadge.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvSimDetail.text = "No active SIM subscription detected"
            binding.tvSimBadge.text = "No SIM"
            binding.tvSimBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#78350F"))
            binding.tvSimBadge.setTextColor(Color.parseColor("#F59E0B"))
        }

        if (audioAudit.directCellularAudioStatus == "PASS") {
            binding.tvDirectAudioDetail.text = "Direct Cellular Audio: Available on device"
            binding.tvDirectAudioDetail.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvDirectAudioDetail.text = "Direct Cellular Audio: Blocked by Android 16 (requires Call Forwarding to Gateway)"
            binding.tvDirectAudioDetail.setTextColor(Color.parseColor("#94A3B8"))
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

        binding.btnDialer.setOnClickListener {
            startActivity(Intent(this, com.edge.reception.ui.DialerActivity::class.java))
        }

        binding.fabDialer.setOnClickListener {
            startActivity(Intent(this, com.edge.reception.ui.DialerActivity::class.java))
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

        var voiceTestManager: com.edge.reception.audio.LocalVoiceTestManager? = null
        var isVoiceTestConnected = false

        binding.btnStartVoiceTest.setOnClickListener {
            if (!isVoiceTestConnected) {
                binding.btnStartVoiceTest.text = "Connecting..."
                binding.btnStartVoiceTest.isEnabled = false
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val client = okhttp3.OkHttpClient()
                        val req = okhttp3.Request.Builder()
                            .url("http://127.0.0.1:8000/v1/voice-test/session")
                            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                            .build()
                        val res = client.newCall(req).execute()
                        if (res.isSuccessful) {
                            val json = org.json.JSONObject(res.body?.string() ?: "{}")
                            val sId = json.getString("session_id")
                            val token = json.getString("pairing_token")
                            val mgr = com.edge.reception.audio.LocalVoiceTestManager(applicationContext)
                            voiceTestManager = mgr
                            mgr.startSession(sId, token) { state ->
                                runOnUiThread {
                                    binding.tvVoiceTestStatus.text = "State: $state"
                                }
                            }
                            runOnUiThread {
                                isVoiceTestConnected = true
                                binding.btnStartVoiceTest.text = "Disconnect"
                                binding.btnStartVoiceTest.isEnabled = true
                                binding.tvVoiceTestStatus.text = "Connected: Open /test-dialer on Mac to speak"
                            }
                        } else {
                            runOnUiThread {
                                binding.btnStartVoiceTest.text = "Connect"
                                binding.btnStartVoiceTest.isEnabled = true
                                binding.tvVoiceTestStatus.text = "Dashboard unreachable. Check USB reverse."
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            binding.btnStartVoiceTest.text = "Connect"
                            binding.btnStartVoiceTest.isEnabled = true
                            binding.tvVoiceTestStatus.text = "Error: ${e.message}"
                        }
                    }
                }
            } else {
                voiceTestManager?.endSession()
                voiceTestManager = null
                isVoiceTestConnected = false
                binding.btnStartVoiceTest.text = "Connect"
                binding.tvVoiceTestStatus.text = "Session ended. Recording saved."
            }
        }

        val carrierCallBridge = com.edge.reception.telephony.CarrierCallBridge(applicationContext)

        fun checkCarrierGateway() {
            lifecycleScope.launch {
                val provStatus = carrierCallBridge.fetchProviderStatus()
                if (provStatus.configured) {
                    binding.tvCarrierBridgeStatus.text = "Connected: DID ${provStatus.inboundDid ?: "N/A"} (${provStatus.provider.uppercase()})"
                    binding.tvCarrierBridgeStatus.setTextColor(Color.parseColor("#10B981"))
                } else {
                    val missing = if (provStatus.missingPrerequisites.isNotEmpty()) {
                        provStatus.missingPrerequisites.joinToString(", ")
                    } else provStatus.state
                    binding.tvCarrierBridgeStatus.text = "PSTN Route [${provStatus.state}]: Missing $missing"
                    binding.tvCarrierBridgeStatus.setTextColor(Color.parseColor("#F59E0B"))
                }
            }
        }

        binding.btnCheckGateway.setOnClickListener {
            checkCarrierGateway()
        }
        checkCarrierGateway()
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

    private fun setupTelephonyAutoAnswerListener() {
        val telephonyManager = getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val telecomManager = getSystemService(android.content.Context.TELECOM_SERVICE) as android.telecom.TelecomManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.CallStateListener {
                    private var hasTriggeredAnswer = false

                    override fun onCallStateChanged(state: Int) {
                        android.util.Log.i("MainActivity", "TelephonyCallback onCallStateChanged: state=$state")
                        if (state == android.telephony.TelephonyManager.CALL_STATE_RINGING && !hasTriggeredAnswer) {
                            hasTriggeredAnswer = true
                            lifecycleScope.launch {
                                val db = AppDatabase.getInstance(applicationContext)
                                val profile = db.businessDao().getProfile()
                                val isEnabled = profile?.autoAnswerEnabled != false
                                val delaySec = profile?.autoAnswerDelaySeconds ?: 3
                                if (isEnabled) {
                                    android.util.Log.i("MainActivity", "Incoming cellular call detected. Auto-answering in $delaySec seconds...")
                                    binding.tvAutoAnswerDesc.text = "Incoming call ringing... answering in ${delaySec}s"
                                    binding.tvAutoAnswerDesc.setTextColor(Color.parseColor("#38BDF8"))
                                    kotlinx.coroutines.delay(delaySec * 1000L)
                                    try {
                                        telecomManager.acceptRingingCall()
                                        android.util.Log.i("MainActivity", "telecomManager.acceptRingingCall() executed successfully.")
                                        binding.tvAutoAnswerDesc.text = "Call answered! Real estate AI active."
                                        binding.tvAutoAnswerDesc.setTextColor(Color.parseColor("#10B981"))
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Error in acceptRingingCall: ${e.message}")
                                    }
                                }
                            }
                        } else if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                            hasTriggeredAnswer = false
                        }
                    }
                }
            )
        }
    }
}
