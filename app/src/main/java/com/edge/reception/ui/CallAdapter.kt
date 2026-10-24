package com.edge.reception.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.edge.reception.data.local.entity.CallEntity
import com.edge.reception.databinding.ItemCallCardBinding

class CallAdapter(
    private val onCallClicked: (CallEntity) -> Unit
) : ListAdapter<CallEntity, CallAdapter.CallViewHolder>(CallDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallViewHolder {
        val binding = ItemCallCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CallViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CallViewHolder(private val binding: ItemCallCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(call: CallEntity) {
            binding.tvCallerId.text = call.callerId

            val timeAgo = DateUtils.getRelativeTimeSpanString(
                call.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            binding.tvTimestamp.text = timeAgo

            val minutes = call.durationSeconds / 60
            val seconds = call.durationSeconds % 60
            binding.tvDuration.text = String.format("%02d:%02d", minutes, seconds)
            binding.tvLanguageBadge.text = call.language
            binding.tvSummary.text = call.summary

            if (call.isDemo) {
                binding.tvDemoBadge.visibility = View.VISIBLE
            } else {
                binding.tvDemoBadge.visibility = View.GONE
            }

            // Outcome badge styling
            when (call.outcome) {
                "LEAD" -> {
                    binding.tvOutcomeBadge.text = "LEAD"
                    binding.tvOutcomeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#064E3B"))
                    binding.tvOutcomeBadge.setTextColor(Color.parseColor("#34D399"))
                }
                "FAIL" -> {
                    binding.tvOutcomeBadge.text = "FAIL"
                    binding.tvOutcomeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#450A0A"))
                    binding.tvOutcomeBadge.setTextColor(Color.parseColor("#F87171"))
                }
                else -> {
                    binding.tvOutcomeBadge.text = "MISSED"
                    binding.tvOutcomeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#451A03"))
                    binding.tvOutcomeBadge.setTextColor(Color.parseColor("#FBBF24"))
                }
            }

            binding.root.setOnClickListener {
                onCallClicked(call)
            }
        }
    }

    class CallDiffCallback : DiffUtil.ItemCallback<CallEntity>() {
        override fun areItemsTheSame(oldItem: CallEntity, newItem: CallEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CallEntity, newItem: CallEntity): Boolean {
            return oldItem == newItem
        }
    }
}
