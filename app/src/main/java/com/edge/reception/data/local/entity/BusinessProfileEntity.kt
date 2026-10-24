package com.edge.reception.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "business_profile")
data class BusinessProfileEntity(
    @PrimaryKey
    val id: Int = 1,
    val businessName: String = "Edge Realty & Services",
    val businessType: String = "Real Estate", // "Real Estate", "Supermarket", "Medical Shop / Pharmacy", "Custom Customer Support"
    val defaultLanguage: String = "English",
    val openingGreeting: String = "Hello, you've reached Edge Realty. I'm the virtual receptionist. How may I help you today?",
    val autoAnswerDelaySeconds: Int = 3,
    val autoAnswerEnabled: Boolean = false,
    val catalogJson: String = "{}"
)
