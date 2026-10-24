package com.edge.reception.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.edge.reception.data.local.dao.BusinessDao
import com.edge.reception.data.local.dao.CallDao
import com.edge.reception.data.local.dao.EntitlementDao
import com.edge.reception.data.local.dao.TranscriptDao
import com.edge.reception.data.local.entity.BusinessProfileEntity
import com.edge.reception.data.local.entity.CallEntity
import com.edge.reception.data.local.entity.EntitlementEntity
import com.edge.reception.data.local.entity.TranscriptTurnEntity

@Database(
    entities = [
        CallEntity::class,
        TranscriptTurnEntity::class,
        BusinessProfileEntity::class,
        EntitlementEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun businessDao(): BusinessDao
    abstract fun entitlementDao(): EntitlementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edge_reception.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
