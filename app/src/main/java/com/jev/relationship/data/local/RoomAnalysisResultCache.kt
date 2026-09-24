package com.jev.relationship.data.local

import com.jev.relationship.domain.realtime.AnalysisResultCache
import com.jev.relationship.ipc.IpcAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class RoomAnalysisResultCache(private val dao: AnalysisResultCacheDao) : AnalysisResultCache {
    override fun conversationResults(conversationHash: String) = flow {
        var before = Long.MAX_VALUE
        while (true) {
            val page = dao.conversationPage(conversationHash, before, 128)
            if (page.isEmpty()) break
            val valid = reusable(page).filterValues { !it.isOutgoing && it.conversationHash == conversationHash }
                .map { (id, result) -> result.copy(messageId = "wechat-8.0.72-$id") }
            if (valid.isNotEmpty()) emit(valid)
            before = page.last().localMessageId
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun findAll(messageIds: Collection<String>): Map<String, IpcAnalysisResult> {
        val ids = messageIds.distinct().mapNotNull { key -> localMessageId(key)?.let { key to it } }.toMap()
        if (ids.isEmpty()) return emptyMap()
        val entries = ids.values.distinct().chunked(500).flatMap { dao.findByMessageIds(it) }
        val reusable = reusable(entries)
        return ids.mapNotNull { (key, id) -> reusable[id]?.let { key to it } }.toMap()
    }

    private suspend fun reusable(entries: List<AnalysisResultCacheEntity>): Map<Long, IpcAnalysisResult> {
        val decoded = entries.mapNotNull { entity ->
            runCatching { entity.toResult() }.getOrNull()?.let { entity to it }
        }
        val legacyHistoryIds = decoded.filterNot { it.first.hasTargetPreservingContext() }
            .mapNotNull { it.second.historyId }.distinct()
        val lengths = legacyHistoryIds.chunked(500).flatMap { dao.sourceTextLengths(it) }
            .associate { it.id to it.textLength }
        return decoded.mapNotNull { (entity, cached) ->
            if (!isReusable(entity, cached, lengths[cached.historyId] ?: 0)) null
            else entity.localMessageId to cached.copy(detailContextual = true)
        }.toMap()
    }

    override suspend fun find(messageId: String): IpcAnalysisResult? {
        val id = localMessageId(messageId) ?: return null
        val entity = dao.findByMessageId(id) ?: return null
        val cached = entity.toResult()
        val historyId = cached.historyId
        val length = if (!entity.hasTargetPreservingContext() && historyId != null)
            dao.sourceTextLength(historyId) ?: 0 else 0
        return cached.copy(detailContextual = true).takeIf { isReusable(entity, cached, length) }
    }

    private fun isReusable(entity: AnalysisResultCacheEntity, cached: IpcAnalysisResult, sourceLength: Int): Boolean {
        // Old prompts kept the FIRST 4000 characters, dropping the actual target.
        // Only distrust legacy entries whose saved source could hit that bug;
        // short inputs and explicitly regenerated entries without a history link stay reusable.
        if (!entity.hasTargetPreservingContext() && cached.historyId != null &&
            sourceLength > LEGACY_CONTEXT_LIMIT) return false
        return cached.detailContextual || !cached.detailIntention.isUnavailableDetail()
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
