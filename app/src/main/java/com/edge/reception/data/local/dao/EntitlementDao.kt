package com.edge.reception.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.edge.reception.data.local.entity.EntitlementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EntitlementDao {
    @Query("SELECT * FROM entitlements WHERE id = 1 LIMIT 1")
    fun getEntitlementFlow(): Flow<EntitlementEntity?>

    @Query("SELECT * FROM entitlements WHERE id = 1 LIMIT 1")
    suspend fun getEntitlement(): EntitlementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEntitlement(entitlement: EntitlementEntity)
}
