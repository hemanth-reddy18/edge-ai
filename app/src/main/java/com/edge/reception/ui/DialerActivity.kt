package com.edge.reception.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.edge.reception.R
import com.edge.reception.databinding.ActivityDialerBinding
import com.edge.reception.telephony.CellularAudioCapabilityTester
import com.edge.reception.telephony.SimManager

class DialerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialerBinding
    private val numberBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detectSimSubscriptions()
        auditCellularAudioCapability()
        setupDialPad()
        setupListeners()
    }

    private fun detectSimSubscriptions() {
        val simStatus = SimManager.detectSubscriptions(this)
        val infoText = StringBuilder()

        if (simStatus.subscriptions.isNotEmpty()) {
            infoText.append("Active Subscriptions (${simStatus.totalCount}):\n")
            for (sub in simStatus.subscriptions) {
                val defaultTag = when {
                    sub.isDefaultVoice && sub.isDefaultData -> " (Default Voice + Data)"
                    sub.isDefaultVoice -> " (Default Voice)"
                    sub.isDefaultData -> " (Default Data)"
                    else -> ""
                }
                infoText.append("• Slot ${sub.slotIndex + 1}: ${sub.carrierName} [${sub.displayName}]$defaultTag\n")
            }
        } else {
            infoText.append("No active SIM subscriptions detected or READ_PHONE_STATE permission pending.")
        }
        binding.tvSimInfo.text = infoText.toString().trim()

        if (simStatus.isNumberExposed && !simStatus.primaryNumber.isNullOrEmpty()) {
            binding.tvPhoneNumberStatus.text = "Carrier Number: ${simStatus.primaryNumber}"
            binding.tvPhoneNumberStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else if (!simStatus.primaryNumber.isNullOrEmpty()) {
            binding.tvPhoneNumberStatus.text = "Confirmed Business Number: ${simStatus.primaryNumber}"
            binding.tvPhoneNumberStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            binding.tvPhoneNumberStatus.text = "Number: Not exposed by SIM (Tap to confirm)"
            binding.tvPhoneNumberStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
        }
    }

    private fun auditCellularAudioCapability() {
        val audit = CellularAudioCapabilityTester.auditDeviceCapability(this)
        if (audit.directCellularAudioStatus == "PASS") {
            binding.tvSimDisclosure.text = "Direct Cellular Audio: Available on this device."
            binding.tvSimDisclosure.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvSimDisclosure.text = "Direct Cellular Audio: Blocked by Android 16 (stock OS restricts CAPTURE_AUDIO_OUTPUT to system apps). Standard cellular calls hand off to Android Phone dialer. For AI reception on your existing SIM, configure Call Forwarding to Edge Gateway."
            binding.tvSimDisclosure.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun setupDialPad() {
        val digitButtons = listOf(
            binding.btn0 to "0",
            binding.btn1 to "1",
            binding.btn2 to "2",
            binding.btn3 to "3",
            binding.btn4 to "4",
            binding.btn5 to "5",
            binding.btn6 to "6",
            binding.btn7 to "7",
            binding.btn8 to "8",
            binding.btn9 to "9",
            binding.btnStar to "*",
            binding.btnHash to "#"
        )

        for ((btn, digit) in digitButtons) {
            btn.setOnClickListener {
                numberBuilder.append(digit)
                updateNumberDisplay()
            }
        }

        binding.btn0.setOnLongClickListener {
            numberBuilder.append("+")
            updateNumberDisplay()
            true
        }

        binding.btnBackspace.setOnClickListener {
            if (numberBuilder.isNotEmpty()) {
                numberBuilder.deleteCharAt(numberBuilder.length - 1)
                updateNumberDisplay()
            }
        }

        binding.btnBackspace.setOnLongClickListener {
            numberBuilder.clear()
            updateNumberDisplay()
            true
        }
    }

    private fun updateNumberDisplay() {
        binding.tvPhoneNumber.text = numberBuilder.toString()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSetBusinessNumber.setOnClickListener {
            showEditNumberDialog()
        }

        binding.rgCallRoute.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbEdgeAiCall.id) {
                binding.btnCall.text = "Start Edge AI Call (VoIP / SIP Gateway)"
                binding.btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
            } else {
                binding.btnCall.text = "Hand off to Native Cellular Dialer"
                binding.btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent))
            }
        }

        binding.btnCall.setOnClickListener {
            val number = numberBuilder.toString().trim()
            if (number.isEmpty()) {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (binding.rbEdgeAiCall.isChecked) {
                // Edge AI Call via VoIP / SIP Gateway
                Toast.makeText(this, "Initiating Edge AI Call to $number via VoIP Gateway...", Toast.LENGTH_SHORT).show()
                val intent = Intent("com.edge.reception.START_CARRIER_CALL").apply {
                    setPackage(packageName)
                    putExtra("CALL_ID", "outbound_call_${System.currentTimeMillis()}")
                    putExtra("CALLER_NUMBER", "Edge AI Receptionist")
                    putExtra("CALLED_DID", number)
                    putExtra("IS_TEST_SESSION", true)
                }
                sendBroadcast(intent)
                finish()
            } else {
                // Truthful Native SIM handoff
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                startActivity(dialIntent)
                finish()
            }
        }
    }

    private fun showEditNumberDialog() {
        val currentNumber = SimManager.getConfirmedBusinessNumber(this) ?: ""
        val input = EditText(this).apply {
            hint = "+91 98765 43210"
            setText(currentNumber)
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Business SIM Number")
            .setMessage("Enter the actual phone number for this device's SIM card so Edge knows its business receiving number:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotEmpty()) {
                    SimManager.saveConfirmedBusinessNumber(this, entered)
                    detectSimSubscriptions()
                    Toast.makeText(this, "Business number confirmed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
