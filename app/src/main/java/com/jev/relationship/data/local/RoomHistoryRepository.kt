package com.jev.relationship.data.local

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.SavedAnalysis
import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.domain.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomHistoryRepository(
    private val dao: AnalysisDao,
) : HistoryRepository {
    override suspend fun save(conversation: Conversation, output: AnalysisOutput, createdAt: Long): Long =
        dao.insert(AnalysisEntity.from(conversation.text, output, createdAt))

    override fun observeAll(): Flow<List<SavedAnalysis>> = dao.observeAll().map { entities ->
        entities.map(AnalysisEntity::toDomain)
    }

    override suspend fun findById(id: Long): SavedAnalysis? = dao.findById(id)?.toDomain()

    override suspend fun delete(id: Long) = dao.deleteById(id)
}

