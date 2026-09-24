package com.jev.relationship.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnalysisEntity::class,
        ContactEntity::class,
        MemoryObservationEntity::class,
        AnalysisResultCacheEntity::class,
        ChatAssistantSessionEntity::class,
        ChatAssistantTurnEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class JevDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao

    abstract fun contactMemoryDao(): ContactMemoryDao

    abstract fun analysisResultCacheDao(): AnalysisResultCacheDao

    abstract fun chatAssistantDao(): ChatAssistantDao
}
