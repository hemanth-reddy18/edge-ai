package com.edge.reception.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "entitlements")
data class EntitlementEntity(
    @PrimaryKey
    val id: Int = 1,
    val clientId: String = "client_s22_demo",
    val isActive: Boolean = true,
    val leaseExpiryTimestamp: Long = System.currentTimeMillis() + (24 * 60 * 60 * 1000), // 24 hours validity
    val signedToken: String = "",
    val killSwitchActive: Boolean = false
)
