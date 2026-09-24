package com.jev.relationship.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnalysisResultCacheDao {
    @Query("SELECT * FROM message_analysis_cache WHERE localMessageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: Long): AnalysisResultCacheEntity?

    @Query("SELECT length(conversationText) FROM analysis_history WHERE id = :historyId LIMIT 1")
    suspend fun sourceTextLength(historyId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: AnalysisResultCacheEntity)
}
