package com.jev.relationship.domain.realtime

import com.jev.relationship.ipc.IpcAnalysisResult

interface AnalysisResultCache {
    suspend fun find(messageId: String): IpcAnalysisResult?

    suspend fun save(messageId: String, result: IpcAnalysisResult)
}
