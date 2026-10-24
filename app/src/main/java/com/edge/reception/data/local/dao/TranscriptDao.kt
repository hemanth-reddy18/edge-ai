package com.edge.reception.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.edge.reception.data.local.entity.TranscriptTurnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: TranscriptTurnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurns(turns: List<TranscriptTurnEntity>)

    @Query("SELECT * FROM transcript_turns WHERE callId = :callId ORDER BY timestampMs ASC")
    fun getTurnsForCall(callId: String): Flow<List<TranscriptTurnEntity>>

    @Query("SELECT * FROM transcript_turns WHERE callId = :callId ORDER BY timestampMs ASC")
    suspend fun getTurnsListForCall(callId: String): List<TranscriptTurnEntity>
}
