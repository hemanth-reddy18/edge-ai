package com.edge.reception.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE business_profile ADD COLUMN voicePersona TEXT NOT NULL DEFAULT 'SOUTH_INDIAN_FEMALE'")
                db.execSQL("ALTER TABLE business_profile ADD COLUMN scriptVersion TEXT NOT NULL DEFAULT '1.0.0'")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edge_reception.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
