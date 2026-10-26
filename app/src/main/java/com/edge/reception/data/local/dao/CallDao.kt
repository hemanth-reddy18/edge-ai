package com.edge.reception.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.edge.reception.data.local.entity.CallEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: CallEntity)

    @Query("SELECT * FROM calls WHERE isTestSession = 0 ORDER BY timestamp DESC")
    fun getAllCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE isTestSession = 1 ORDER BY timestamp DESC")
    fun getTestCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE isTestSession = 0 AND outcome = :outcome ORDER BY timestamp DESC")
    fun getCallsByOutcome(outcome: String): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE id = :id LIMIT 1")
    suspend fun getCallById(id: String): CallEntity?

    @Query("SELECT COUNT(*) FROM calls WHERE isTestSession = 0")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE isTestSession = 0 AND outcome = 'LEAD'")
    fun getLeadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE isTestSession = 0 AND outcome = 'FAIL'")
    fun getFailCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE isTestSession = 0 AND outcome = 'MISSED'")
    fun getMissedCount(): Flow<Int>

    @Query("DELETE FROM calls WHERE isDemo = 1")
    suspend fun deleteDemoCalls()
}
