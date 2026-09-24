package com.jev.relationship.domain

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.SavedAnalysis
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    suspend fun save(conversation: Conversation, output: AnalysisOutput, createdAt: Long = System.currentTimeMillis()): Long

    fun observeAll(): Flow<List<SavedAnalysis>>

    suspend fun findById(id: Long): SavedAnalysis?

    suspend fun delete(id: Long)
}

