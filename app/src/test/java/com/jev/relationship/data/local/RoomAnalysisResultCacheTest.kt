package com.jev.relationship.data.local

import com.jev.relationship.ipc.IpcAnalysisResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomAnalysisResultCacheTest {
    @Test
    fun failedOrLegacyResultsAreNotReusedAsSuccessfulContextualCards() = runBlocking {
        val fallback = result(detailContextual = false)
        val dao = RecordingDao(AnalysisResultCacheEntity.from(42L, fallback))
        val cache = RoomAnalysisResultCache(dao)

        assertNull(cache.find("wechat-8.0.72-42"))
        cache.save("wechat-8.0.72-42", fallback)
        assertEquals(0, dao.saveCount)
    }

    @Test
    fun successfulContextualResultsArePersistedAndRestored() = runBlocking {
        val expected = result(detailContextual = true)
        val dao = RecordingDao(null)
        val cache = RoomAnalysisResultCache(dao)

        cache.save("wechat-8.0.72-42", expected)

        assertEquals(expected, cache.find("wechat-8.0.72-42"))
        assertEquals(1, dao.saveCount)
    }

    @Test
    fun successfulLegacyResultsRemainReusableAfterContextFlagWasAdded() = runBlocking {
        val legacySuccess = result(detailContextual = false).copy(detailIntention = "她希望你解释之前的约定。")
        val cache = RoomAnalysisResultCache(RecordingDao(AnalysisResultCacheEntity.from(42L, legacySuccess)))

        assertEquals(true, cache.find("wechat-8.0.72-42")?.detailContextual)
    }

    private fun result(detailContextual: Boolean) = IpcAnalysisResult(
        messageId = "wechat-8.0.72-42",
        conversationHash = "conversation",
        textHash = "text",
        isOutgoing = false,
        emotion = "平静",
        intents = emptyList(),
        riskLevel = 0,
        suggestion = "",
        detailIntention = if (detailContextual) "她希望你解释清楚。" else "理解模型已配置，但本次调用失败。",
        detailContextual = detailContextual,
    )

    private class RecordingDao(var entity: AnalysisResultCacheEntity?) : AnalysisResultCacheDao {
        var saveCount = 0

        override suspend fun findByMessageId(messageId: Long): AnalysisResultCacheEntity? =
            entity?.takeIf { it.localMessageId == messageId }

        override suspend fun sourceTextLength(historyId: Long): Int? = null

        override suspend fun save(entity: AnalysisResultCacheEntity) {
            saveCount += 1
            this.entity = entity
        }
    }
}
