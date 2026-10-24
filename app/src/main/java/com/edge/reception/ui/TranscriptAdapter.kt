package com.edge.reception.ui

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.edge.reception.data.local.entity.TranscriptTurnEntity
import com.edge.reception.databinding.ItemTranscriptTurnBinding

class TranscriptAdapter : ListAdapter<TranscriptTurnEntity, TranscriptAdapter.TranscriptViewHolder>(TurnDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TranscriptViewHolder {
        val binding = ItemTranscriptTurnBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TranscriptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TranscriptViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TranscriptViewHolder(private val binding: ItemTranscriptTurnBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(turn: TranscriptTurnEntity) {
            val isEdge = turn.speaker.equals("EDGE", ignoreCase = true)

            binding.tvSpeakerTag.text = if (isEdge) "EDGE RECEPTIONIST" else "CUSTOMER"
            binding.tvOriginalSpeech.text = turn.recognizedText
            binding.tvTranslation.text = "→ " + turn.englishTranslation

            val totalSec = turn.timestampMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            binding.tvTurnTime.text = String.format("%02d:%02d", min, sec)

            val confPercent = (turn.confidence * 100).toInt()
            binding.tvTurnMeta.text = "${turn.detectedLanguage} • ${confPercent}% confidence"

            val params = binding.cardBubble.layoutParams as LinearLayout.LayoutParams
            if (isEdge) {
                params.gravity = Gravity.END
                params.setMargins(64, 0, 0, 0)
                binding.cardBubble.setCardBackgroundColor(Color.parseColor("#0369A1"))
                binding.tvSpeakerTag.setTextColor(Color.parseColor("#7DD3FC"))
            } else {
                params.gravity = Gravity.START
                params.setMargins(0, 0, 64, 0)
                binding.cardBubble.setCardBackgroundColor(Color.parseColor("#334155"))
                binding.tvSpeakerTag.setTextColor(Color.parseColor("#38BDF8"))
            }
            binding.cardBubble.layoutParams = params
        }
    }

    class TurnDiffCallback : DiffUtil.ItemCallback<TranscriptTurnEntity>() {
        override fun areItemsTheSame(oldItem: TranscriptTurnEntity, newItem: TranscriptTurnEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TranscriptTurnEntity, newItem: TranscriptTurnEntity): Boolean {
            return oldItem == newItem
        }
    }
}
