package com.jev.relationship.data.local

import com.jev.relationship.domain.realtime.AnalysisResultCache
import com.jev.relationship.ipc.IpcAnalysisResult

class RoomAnalysisResultCache(private val dao: AnalysisResultCacheDao) : AnalysisResultCache {
    override suspend fun find(messageId: String): IpcAnalysisResult? {
        val id = localMessageId(messageId) ?: return null
        val entity = dao.findByMessageId(id) ?: return null
        val cached = entity.toResult()
        val historyId = cached.historyId
        // Old prompts kept the FIRST 4000 characters, dropping the actual target.
        // Only distrust legacy entries whose saved source could hit that bug;
        // short inputs and explicitly regenerated entries without a history link stay reusable.
        if (!entity.hasTargetPreservingContext() && historyId != null &&
            (dao.sourceTextLength(historyId) ?: 0) > LEGACY_CONTEXT_LIMIT) return null
        if (!cached.detailContextual && cached.detailIntention.isUnavailableDetail()) return null
        return cached.copy(detailContextual = true)
    }

    override suspend fun save(messageId: String, result: IpcAnalysisResult) {
        if (!result.detailContextual) return
        val id = localMessageId(messageId) ?: return
        dao.save(AnalysisResultCacheEntity.from(id, result))
    }

    private fun localMessageId(messageId: String): Long? {
        val suffix = when {
            messageId.startsWith("wechat-local-") -> messageId.substringAfterLast('-')
            messageId.startsWith("wechat-8.0.72-") -> messageId.removePrefix("wechat-8.0.72-")
            else -> return null
        }
        return suffix.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun String.isUnavailableDetail(): Boolean = isBlank() || UNAVAILABLE_DETAIL_PREFIXES.any(::startsWith)

    private companion object {
        const val LEGACY_CONTEXT_LIMIT = 4_000
        val UNAVAILABLE_DETAIL_PREFIXES = listOf(
            "理解模型已配置，但本次调用失败",
            "未配置理解模型，暂时只能给出结构化判断",
        )
    }
}
