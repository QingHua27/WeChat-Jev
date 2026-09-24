package com.jev.relationship.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnalysisResultCacheDao {
    @Query("SELECT * FROM message_analysis_cache WHERE conversationHash = :conversationHash AND localMessageId < :beforeId ORDER BY localMessageId DESC LIMIT :limit")
    suspend fun conversationPage(conversationHash: String, beforeId: Long, limit: Int): List<AnalysisResultCacheEntity>
    @Query("SELECT * FROM message_analysis_cache WHERE localMessageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: Long): AnalysisResultCacheEntity?

    @Query("SELECT * FROM message_analysis_cache WHERE localMessageId IN (:messageIds)")
    suspend fun findByMessageIds(messageIds: List<Long>): List<AnalysisResultCacheEntity>

    @Query("SELECT id, length(conversationText) AS textLength FROM analysis_history WHERE id IN (:historyIds)")
    suspend fun sourceTextLengths(historyIds: List<Long>): List<CacheHistoryLength>

    @Query("SELECT length(conversationText) FROM analysis_history WHERE id = :historyId LIMIT 1")
    suspend fun sourceTextLength(historyId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: AnalysisResultCacheEntity)
}

data class CacheHistoryLength(val id: Long, val textLength: Int)
