package com.jev.relationship.domain.realtime

import com.jev.relationship.domain.inline.InlineTextHasher
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcAnalysisResultSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCachePreloaderTest {
    @Test fun `fast preload is cached only deduplicated and cancelled on conversation change`() = runTest {
        val reads = mutableListOf<String>()
        val published = mutableListOf<IpcAnalysisResult>()
        val cache = object : AnalysisResultCache {
            override suspend fun find(messageId: String): IpcAnalysisResult? = null
            override suspend fun save(messageId: String, result: IpcAnalysisResult) = Unit
            override fun conversationResults(conversationHash: String) = flow {
                reads += conversationHash
                emit(listOf(result(1, conversationHash)))
                delay(100)
                emit(listOf(result(2, conversationHash)))
            }
        }
        val sink = object : IpcAnalysisResultSink {
            override fun publish(result: IpcAnalysisResult) { published += result }
            override fun clear() = Unit
        }
        val loader = ConversationCachePreloader(cache, sink, backgroundScope)
        loader.preload("a", true)
        runCurrent()
        loader.preload("a", true)
        loader.preload("b", true)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(InlineTextHasher.hash("a"), InlineTextHasher.hash("b")), reads)
        assertEquals(1, published.count { it.conversationHash == InlineTextHasher.hash("a") })
        assertEquals(2, published.count { it.conversationHash == InlineTextHasher.hash("b") })
        loader.preload("c", false)
        runCurrent()
        assertEquals(2, reads.size)
        assertTrue(published.all { it.detailContextual })
    }

    private fun result(id: Int, hash: String) = IpcAnalysisResult(
        messageId = "wechat-8.0.72-$id", conversationHash = hash, textHash = "text",
        isOutgoing = false, emotion = "平静", intents = emptyList(), riskLevel = 0,
        suggestion = "", detailIntention = "已缓存解读", detailContextual = true)
}
