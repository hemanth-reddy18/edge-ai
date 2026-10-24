package com.edge.reception.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.edge.reception.data.local.AppDatabase
import com.edge.reception.data.repository.EdgeRepository
import com.edge.reception.databinding.ActivityCallDetailBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CallDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallDetailBinding
    private lateinit var repository: EdgeRepository
    private val transcriptAdapter = TranscriptAdapter()

    private var isPlaying = false
    private var currentPositionSeconds = 0
    private var totalDurationSeconds = 62
    private var playbackSpeed = 1.0f

    private val handler = Handler(Looper.getMainLooper())
    private val playbackRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && currentPositionSeconds < totalDurationSeconds) {
                currentPositionSeconds += 1
                updatePlayerUi()
                handler.postDelayed(this, (1000 / playbackSpeed).toLong())
            } else if (currentPositionSeconds >= totalDurationSeconds) {
                isPlaying = false
                currentPositionSeconds = 0
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                updatePlayerUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = EdgeRepository(db)

        val callId = intent.getStringExtra("EXTRA_CALL_ID") ?: ""

        setupRecyclerView()
        setupListeners()
        loadCallDetails(callId)
    }

    private fun setupRecyclerView() {
        binding.rvTranscript.layoutManager = LinearLayoutManager(this)
        binding.rvTranscript.adapter = transcriptAdapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            if (isPlaying) {
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(playbackRunnable)
            } else {
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(playbackRunnable)
            }
        }

        binding.btnSpeed1x.setOnClickListener { setSpeed(1.0f) }
        binding.btnSpeed15x.setOnClickListener { setSpeed(1.5f) }
        binding.btnSpeed2x.setOnClickListener { setSpeed(2.0f) }

        binding.btnReportIssue.setOnClickListener {
            showReportIssueDialog()
        }
    }

    private fun setSpeed(speed: Float) {
        playbackSpeed = speed
        binding.btnSpeed1x.backgroundTintList = ColorStateList.valueOf(if (speed == 1.0f) Color.parseColor("#38BDF8") else Color.parseColor("#0B0F19"))
        binding.btnSpeed1x.setTextColor(if (speed == 1.0f) Color.parseColor("#020617") else Color.parseColor("#94A3B8"))

        binding.btnSpeed15x.backgroundTintList = ColorStateList.valueOf(if (speed == 1.5f) Color.parseColor("#38BDF8") else Color.parseColor("#0B0F19"))
        binding.btnSpeed15x.setTextColor(if (speed == 1.5f) Color.parseColor("#020617") else Color.parseColor("#94A3B8"))

        binding.btnSpeed2x.backgroundTintList = ColorStateList.valueOf(if (speed == 2.0f) Color.parseColor("#38BDF8") else Color.parseColor("#0B0F19"))
        binding.btnSpeed2x.setTextColor(if (speed == 2.0f) Color.parseColor("#020617") else Color.parseColor("#94A3B8"))
    }

    private fun updatePlayerUi() {
        val curMin = currentPositionSeconds / 60
        val curSec = currentPositionSeconds % 60
        val totMin = totalDurationSeconds / 60
        val totSec = totalDurationSeconds % 60

        binding.tvAudioTime.text = String.format("%02d:%02d / %02d:%02d", curMin, curSec, totMin, totSec)
        val progress = if (totalDurationSeconds > 0) ((currentPositionSeconds.toFloat() / totalDurationSeconds) * 100).toInt() else 0
        binding.seekBarAudio.progress = progress
    }

    private fun loadCallDetails(callId: String) {
        lifecycleScope.launch {
            val call = repository.getCallById(callId)
            if (call != null) {
                totalDurationSeconds = call.durationSeconds.coerceAtLeast(1)
                updatePlayerUi()

                binding.tvDetailCallerId.text = call.callerId
                val min = call.durationSeconds / 60
                val sec = call.durationSeconds % 60
                binding.tvDetailMeta.text = "${call.language} • ${String.format("%02d:%02d", min, sec)}"
                binding.tvDetailSummary.text = call.summary

                if (call.outcome == "LEAD") {
                    binding.tvDetailOutcomeBadge.text = "LEAD"
                    binding.tvDetailOutcomeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#064E3B"))
                    binding.tvDetailOutcomeBadge.setTextColor(Color.parseColor("#34D399"))
                    binding.tvDetailIntent.text = "Intent: High-Interest Customer Booking / Purchase Inquiry"
                    binding.tvDetailNextAction.text = "Next Action: Sales team follow-up within 1 hour."
                } else {
                    binding.tvDetailOutcomeBadge.text = "FAIL"
                    binding.tvDetailOutcomeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#450A0A"))
                    binding.tvDetailOutcomeBadge.setTextColor(Color.parseColor("#F87171"))
                    binding.tvDetailIntent.text = "Intent: Wrong Number / Non-actionable Request"
                    binding.tvDetailNextAction.text = "Next Action: No sales follow-up required."
                }
            }
        }

        lifecycleScope.launch {
            repository.getTranscriptForCall(callId).collectLatest { turns ->
                transcriptAdapter.submitList(turns)
            }
        }
    }

    private fun showReportIssueDialog() {
        AlertDialog.Builder(this)
            .setTitle("Report Call Issue")
            .setMessage("This will submit an anonymous diagnostic log with call ID and model telemetry to the Founder Control Plane. Audio and transcript will NOT be uploaded without explicit consent.")
            .setPositiveButton("Submit Report") { _, _ ->
                Toast.makeText(this, "Diagnostic report sent to Founder Dashboard.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(playbackRunnable)
    }
}
