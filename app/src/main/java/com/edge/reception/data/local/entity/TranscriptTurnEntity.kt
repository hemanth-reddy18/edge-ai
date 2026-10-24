package com.edge.reception.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcript_turns")
data class TranscriptTurnEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val callId: String,
    val speaker: String, // "CUSTOMER" or "EDGE"
    val recognizedText: String,
    val englishTranslation: String,
    val detectedLanguage: String,
    val timestampMs: Long,
    val confidence: Float = 0.95f
)
