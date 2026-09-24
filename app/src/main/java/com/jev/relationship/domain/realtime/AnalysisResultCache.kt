package com.jev.relationship.domain.realtime

import com.jev.relationship.ipc.IpcAnalysisResult

interface AnalysisResultCache {
    fun conversationResults(conversationHash: String): kotlinx.coroutines.flow.Flow<List<IpcAnalysisResult>> =
        kotlinx.coroutines.flow.emptyFlow()
    suspend fun find(messageId: String): IpcAnalysisResult?

    suspend fun findAll(messageIds: Collection<String>): Map<String, IpcAnalysisResult> =
        messageIds.distinct().mapNotNull { id -> find(id)?.let { id to it } }.toMap()

    suspend fun save(messageId: String, result: IpcAnalysisResult)
}
