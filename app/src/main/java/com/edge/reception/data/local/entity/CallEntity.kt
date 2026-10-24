package com.edge.reception.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calls")
data class CallEntity(
    @PrimaryKey
    val id: String,
    val callerId: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val language: String,
    val isLead: Boolean,
    val outcome: String, // "LEAD", "FAIL", "MISSED"
    val summary: String,
    val audioRecordingPath: String?,
    val isDemo: Boolean = false,
    val synced: Boolean = false
)
