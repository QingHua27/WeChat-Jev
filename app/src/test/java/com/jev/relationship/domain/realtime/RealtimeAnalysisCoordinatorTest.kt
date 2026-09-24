package com.jev.relationship.domain.realtime

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.SavedAnalysis
import com.jev.relationship.core.model.ReplyTone
import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.domain.inline.InlineAnalysisCardPublisher
import com.jev.relationship.domain.inline.InlineCardStore
import com.jev.relationship.domain.source.VisibleChatConversation
import com.jev.relationship.domain.source.VisibleChatMessage
import com.jev.relationship.domain.HistoryRepository
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.domain.surface.AssistantSurfaceState
import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcAnalysisResultSink
import com.jev.relationship.ipc.MessageCaptureCoordinator
import com.jev.relationship.ipc.MessageSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeAnalysisCoordinatorTest {
    @Test fun `a batch miss filled while another target is analyzed is reused without a second model call`() = runTest {
        val cache = RecordingAnalysisResultCache()
        val records = (1L..2L).map { com.jev.relationship.ipc.LocalChatRecord(it, "消息$it", it, false) }
        var calls = 0
        val analyzer = ConversationAnalyzer {
            calls++
            cache.save("wechat-8.0.72-1", IpcAnalysisResult("wechat-8.0.72-1", "chat", "text", false,
                emotion = "平静", intents = emptyList(), riskLevel = 0, suggestion = "保持清晰", detailContextual = true))
            output()
        }
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            resultCache = cache, localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records })
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(VisibleChatConversation("chat", "聊天", "消息2", false,
            records.map { VisibleChatMessage(it.text, false, 0, localMessageId = it.id) }))
        advanceTimeBy(701); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun `history cache hits use one batch lookup and one batch delivery without analysis`() = runTest {
        val records = (1L..4L).map { com.jev.relationship.ipc.LocalChatRecord(it, "消息$it", it, false) }
        val values = records.associate { record -> "wechat-8.0.72-${record.id}" to IpcAnalysisResult(
            "wechat-8.0.72-${record.id}", "chat", "text", false, emotion = "平静", intents = emptyList(),
            riskLevel = 0, suggestion = "保持清晰", detailContextual = true) }
        var singleReads = 0
        var batchReads = 0
        val cache = object : AnalysisResultCache {
            override suspend fun find(messageId: String): IpcAnalysisResult? { singleReads++; return values[messageId] }
            override suspend fun findAll(messageIds: Collection<String>): Map<String, IpcAnalysisResult> {
                batchReads++; return values.filterKeys { it in messageIds }
            }
            override suspend fun save(messageId: String, result: IpcAnalysisResult) = Unit
        }
        val sink = RecordingAnalysisResultSink()
        val analyzer = RecordingAnalyzer()
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            resultCache = cache, resultSink = sink,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records })
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(VisibleChatConversation("chat", "聊天", "消息4", false,
            records.map { VisibleChatMessage(it.text, false, 0, localMessageId = it.id) }))
        runCurrent() // Fast visible-cache recovery is separate from the history sweep.
        singleReads = 0; batchReads = 0; sink.batches.clear(); sink.results.clear()
        advanceTimeBy(701); runCurrent()
        assertEquals("history must fetch cached targets together", 1, batchReads)
        assertEquals(0, singleReads)
        assertEquals(1, sink.batches.size)
        assertEquals(4, sink.batches.single().size)
        assertTrue(analyzer.calls.isEmpty())
    }

    @Test
    fun `reply suggestion uses latest incoming even when viewing old messages and reuses its cache`() = runTest {
        val sink = RecordingAnalysisResultSink()
        val cache = RecordingAnalysisResultCache()
        val records = listOf(
            com.jev.relationship.ipc.LocalChatRecord(1, "旧消息", 1, false),
            com.jev.relationship.ipc.LocalChatRecord(2, "最新问题", 2, false),
            com.jev.relationship.ipc.LocalChatRecord(3, "我的话", 3, true),
        )
        var calls = 0
        val analyzer = ConversationAnalyzer { conversation ->
            calls++
            if (conversation.text.endsWith("最新问题")) {
                assertTrue(conversation.text.contains("对方：旧消息"))
                assertTrue(conversation.hasBoundedMessageContext)
            }
            output().let { it.copy(analysis = it.analysis.copy(detailed =
                com.jev.relationship.core.model.DetailedAnalysis(contextual = true, reply =
                    if (conversation.text.endsWith("最新问题")) "最新建议" else "旧建议"))) }
        }
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope, resultSink = sink,
            resultCache = cache, localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records })
        fun snapshot() = VisibleChatConversation("chat", "旧消息", "旧消息", false,
            listOf(VisibleChatMessage("旧消息", false, 0, localMessageId = 1)))
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700); runCurrent()
        assertEquals("最新建议", sink.replySuggestions.last().text)
        assertEquals("wechat-8.0.72-2", sink.replySuggestions.last().messageId)
        coordinator.leaveVisibleConversation()
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700); runCurrent()
        assertEquals(2, calls)
        assertEquals("最新建议", sink.replySuggestions.last().text)
    }

    @Test
    fun `model failure pauses automatic and manual batches without caching errors`() = runTest {
        var calls = 0
        val analyzer = ConversationAnalyzer {
            calls++
            output().let { it.copy(analysis = it.analysis.copy(detailed =
                com.jev.relationship.core.model.DetailedAnalysis(intention = "模型服务限流（HTTP 429）", failureReason = "模型服务限流（HTTP 429）"))) }
        }
        val cache = RecordingAnalysisResultCache()
        val sink = RecordingAnalysisResultSink()
        val records = (1L..10L).map { com.jev.relationship.ipc.LocalChatRecord(it, "消息[$it]", it, false) }
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope, resultCache = cache,
            resultSink = sink, localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records })
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(VisibleChatConversation("chat", "消息[10]", "消息[10]", false,
            listOf(VisibleChatMessage("消息[10]", false, 0, localMessageId = 10))))
        advanceTimeBy(700); runCurrent()
        assertEquals(1, calls)
        assertTrue(cache.savedMessageIds.isEmpty())
        assertTrue(sink.results.last().detailIntention.contains("HTTP 429"))
        assertTrue(coordinator.state.value is RealtimeAnalysisState.Error)
        var status = ""
        coordinator.regenerateRecentConversation("chat") { status = it }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(status.contains("HTTP 429"))
        assertTrue(cache.savedMessageIds.isEmpty())
    }

    @Test
    fun `entering chat fills latest sixty once and scrolling fills only the uncached older target`() = runTest {
        val analyzer = RecordingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val records = (1L..100L).map {
            com.jev.relationship.ipc.LocalChatRecord(it, "消息[$it]", it, it % 2 == 0L)
        }
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records },
            resultCache = cache)
        fun snapshot(id: Long) = VisibleChatConversation("chat", "消息[$id]", "消息[$id]", false,
            listOf(VisibleChatMessage("消息[$id]", false, 0, localMessageId = id)))
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(snapshot(99))
        advanceTimeBy(700); runCurrent()
        assertEquals(30, analyzer.calls.size)
        assertEquals((41L..99L step 2).toSet(), cache.savedMessageIds.map { it.substringAfterLast('-').toLong() }.toSet())
        coordinator.leaveVisibleConversation()
        coordinator.submitVisibleConversation(snapshot(99))
        advanceTimeBy(700); runCurrent()
        assertEquals(30, analyzer.calls.size)
        coordinator.submitVisibleConversation(snapshot(9))
        advanceTimeBy(700); runCurrent()
        assertEquals(31, analyzer.calls.size)
        assertTrue(analyzer.calls.last().text.endsWith("当前待分析消息：消息[9]"))
        assertFalse(analyzer.calls.last().text.contains("消息[10]"))
    }

    @Test
    fun `visible analysis reads complete local history instead of screen fragment`() = runTest {
        val analyzer = RecordingAnalyzer()
        val local = com.jev.relationship.domain.source.LocalConversationHistory { id, title ->
            assertEquals("", id)
            assertEquals("chat", title)
            listOf(com.jev.relationship.ipc.LocalChatRecord(1, "屏幕外的约定", 1000, true), com.jev.relationship.ipc.LocalChatRecord(2, "还记得吗", 2000, false))
        }
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope, localHistory = local)
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(VisibleChatConversation("chat", "还记得吗", "还记得吗", false, listOf(VisibleChatMessage("还记得吗", false, 0))))
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, analyzer.calls.size)
        assertTrue(analyzer.calls.single().text.contains("我：屏幕外的约定"))
    }

    @Test
    fun `scrolling back to an identified message reuses saved analysis`() = runTest {
        val analyzer = RecordingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val sink = RecordingAnalysisResultSink()
        val local = com.jev.relationship.domain.source.LocalConversationHistory { _, _ ->
            listOf(com.jev.relationship.ipc.LocalChatRecord(42, "目标消息", 1000, false))
        }
        val coordinator = fixture(
            MessageCaptureCoordinator(), analyzer, resultSink = sink, scope = backgroundScope,
            localHistory = local, resultCache = cache,
        )
        val target = VisibleChatMessage("目标消息", false, 0, localMessageId = 42)
        val snapshot = VisibleChatConversation("chat", "目标消息", "目标消息", false, listOf(target))
        coordinator.setEnabled(true)

        coordinator.submitVisibleConversation(snapshot)
        advanceTimeBy(700)
        runCurrent()
        coordinator.invalidateVisibleViewport()
        coordinator.submitVisibleConversation(snapshot)
        advanceTimeBy(700)
        runCurrent()

        assertEquals(1, analyzer.calls.size)
        assertEquals(1, cache.findCount)
        assertEquals(3, cache.batchFindCount)
        assertEquals(2, sink.results.size)
    }

    @Test
    fun `entering conversation backfills incoming messages newest first and only renders visible cards`() = runTest {
        val analyzer = RecordingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val sink = RecordingAnalysisResultSink()
        val history = listOf(
            com.jev.relationship.ipc.LocalChatRecord(10, "较早的对方消息", 1000, false),
            com.jev.relationship.ipc.LocalChatRecord(11, "我的回复", 1500, true),
            com.jev.relationship.ipc.LocalChatRecord(12, "最新的对方消息", 2000, false),
            com.jev.relationship.ipc.LocalChatRecord(13, "对方撤回了一条消息", 2500, false),
        )
        val coordinator = fixture(
            capture = MessageCaptureCoordinator(), analyzer = analyzer, resultSink = sink,
            scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> history },
            resultCache = cache,
        )
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "chat", conversationText = "最新的对方消息",
                anchorText = "最新的对方消息", anchorIsOutgoing = false,
                messages = listOf(VisibleChatMessage("最新的对方消息", false, 0, localMessageId = 12)),
            ),
        )
        advanceTimeBy(700)
        runCurrent()

        assertEquals(2, analyzer.calls.size)
        assertTrue(analyzer.calls[0].text.endsWith("当前待分析消息：最新的对方消息"))
        assertTrue(analyzer.calls[0].text.contains("我：我的回复"))
        assertTrue(analyzer.calls[1].text.endsWith("当前待分析消息：较早的对方消息"))
        assertEquals(listOf("wechat-8.0.72-12", "wechat-8.0.72-10"), cache.savedMessageIds)
        assertEquals(listOf("wechat-8.0.72-12"), sink.results.map { it.messageId })

        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "chat", conversationText = "较早的对方消息",
                anchorText = "较早的对方消息", anchorIsOutgoing = false,
                messages = listOf(VisibleChatMessage("较早的对方消息", false, 0, localMessageId = 10)),
            ),
        )
        advanceTimeBy(700)
        runCurrent()

        assertEquals(2, analyzer.calls.size)
        assertEquals(setOf("wechat-8.0.72-12", "wechat-8.0.72-10"), sink.results.map { it.messageId }.toSet())

        coordinator.leaveVisibleConversation()
        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "chat", conversationText = "最新的对方消息",
                anchorText = "最新的对方消息", anchorIsOutgoing = false,
                messages = listOf(VisibleChatMessage("最新的对方消息", false, 0, localMessageId = 12)),
            ),
        )
        advanceTimeBy(700)
        runCurrent()

        assertEquals("re-entering a chat only restores the cached card", 2, analyzer.calls.size)
        assertEquals(2, cache.savedMessageIds.size)
    }

    @Test
    fun `switch and coordinator restart reuse cached messages and analyze only new incoming records`() = runTest {
        val analyzer = RecordingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val sink = RecordingAnalysisResultSink()
        val records = mutableListOf(com.jev.relationship.ipc.LocalChatRecord(42, "已解析消息", 1000, false))
        val local = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records.toList() }
        fun coordinator() = fixture(MessageCaptureCoordinator(), analyzer, resultSink = sink,
            scope = backgroundScope, localHistory = local, resultCache = cache).apply { setEnabled(true) }
        fun snapshot() = VisibleChatConversation("chat", "聊天", "聊天", false,
            records.map { VisibleChatMessage(it.text, it.isOutgoing, 0, localMessageId = it.id) })
        val first = coordinator()
        first.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, analyzer.calls.size)

        first.setConversationAnalysisEnabled("chat", "chat", false)
        first.submitVisibleConversation(snapshot())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, analyzer.calls.size)
        first.setConversationAnalysisEnabled("chat", "chat", true)
        first.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        assertEquals("switching on must not call a model for cached records", 1, analyzer.calls.size)

        first.stop()
        records += com.jev.relationship.ipc.LocalChatRecord(43, "新消息", 2000, false)
        val restarted = coordinator()
        restarted.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        assertEquals(2, analyzer.calls.size)
        assertEquals(listOf("wechat-8.0.72-42", "wechat-8.0.72-43"), cache.savedMessageIds)
        assertEquals(setOf("wechat-8.0.72-42", "wechat-8.0.72-43"), sink.results.map { it.messageId }.toSet())
    }

    @Test
    fun `new visible message after initial sweep is analyzed once without a capture callback`() = runTest {
        val analyzer = RecordingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val records = mutableListOf(com.jev.relationship.ipc.LocalChatRecord(42, "旧消息", 1000, false))
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records.toList() },
            resultCache = cache)
        fun snapshot() = VisibleChatConversation("chat", "聊天", "聊天", false,
            records.map { VisibleChatMessage(it.text, false, 0, localMessageId = it.id) })
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        records += com.jev.relationship.ipc.LocalChatRecord(43, "刚收到的消息", 2000, false)
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        assertEquals("a new row must be analyzed even after the first sweep completed", 2, analyzer.calls.size)
        assertEquals(listOf("wechat-8.0.72-42", "wechat-8.0.72-43"), cache.savedMessageIds)
    }

    @Test
    fun `viewport refresh does not duplicate a live message already being analyzed`() = runTest {
        val capture = MessageCaptureCoordinator().apply { activate() }
        val analyzer = BlockingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val records = mutableListOf(com.jev.relationship.ipc.LocalChatRecord(42, "旧消息", 1000, false))
        val coordinator = fixture(capture, analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records.toList() },
            resultCache = cache)
        fun snapshot() = VisibleChatConversation("chat", "聊天", "聊天", false,
            records.map { VisibleChatMessage(it.text, false, 0, localMessageId = it.id) })
        coordinator.start()
        runCurrent()
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        analyzer.results[0].complete(output())
        runCurrent()

        records += com.jev.relationship.ipc.LocalChatRecord(43, "新消息", 2000, false)
        capture.submit(validMessage("chat", "新消息", 2000).copy(messageId = "wechat-8.0.72-43"))
        runCurrent()
        advanceTimeBy(700)
        runCurrent()
        assertEquals(2, analyzer.results.size)
        coordinator.submitVisibleConversation(snapshot())
        advanceTimeBy(700)
        runCurrent()
        val requestCount = analyzer.results.size
        analyzer.results.forEach { it.complete(output()) }
        runCurrent()
        assertEquals("live capture and viewport must share one model call per message", 2, requestCount)
        assertEquals(listOf("wechat-8.0.72-42", "wechat-8.0.72-43"), cache.savedMessageIds)
    }

    @Test
    fun `manual regeneration replaces only incoming results among the latest sixty messages`() = runTest {
        val calls = mutableListOf<Conversation>()
        val analyzer = ConversationAnalyzer { conversation ->
            calls += conversation
            output().let { it.copy(analysis = it.analysis.copy(detailed =
                com.jev.relationship.core.model.DetailedAnalysis(intention = "新的解读", contextual = true))) }
        }
        val cache = RecordingAnalysisResultCache()
        val records = (1L..65L).map { com.jev.relationship.ipc.LocalChatRecord(it, "消息$it", it * 1000, it % 2 == 0L) }
        val old = IpcAnalysisResult("wechat-8.0.72-1", "chat", "text", false, emotion = "平静", intents = emptyList(), riskLevel = 1, suggestion = "",
            detailIntention = "已有解读", detailContextual = true)
        records.filterNot { it.isOutgoing }.forEach { cache.save("wechat-8.0.72-${it.id}", old.copy(messageId = "wechat-8.0.72-${it.id}")) }
        cache.savedMessageIds.clear()
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> records }, resultCache = cache)
        coordinator.setEnabled(true)
        var status = ""
        assertTrue(coordinator.regenerateRecentConversation("chat") { status = it })
        runCurrent()
        assertEquals(30, calls.size)
        assertEquals((7L..65L step 2).map { "wechat-8.0.72-$it" }.toSet(), cache.savedMessageIds.toSet())
        assertEquals("已有解读", cache.find("wechat-8.0.72-1")?.detailIntention)
        assertEquals("新的解读", cache.find("wechat-8.0.72-65")?.detailIntention)
        assertTrue(status.contains("30"))
    }

    @Test
    fun `failed manual regeneration preserves successful cached interpretation`() = runTest {
        val cache = RecordingAnalysisResultCache()
        val old = IpcAnalysisResult("wechat-8.0.72-42", "chat", "text", false, emotion = "平静", intents = emptyList(), riskLevel = 1, suggestion = "",
            detailIntention = "旧解读", detailContextual = true)
        cache.save(old.messageId, old)
        cache.savedMessageIds.clear()
        val coordinator = fixture(MessageCaptureCoordinator(), ConversationAnalyzer { output() }, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ ->
                listOf(com.jev.relationship.ipc.LocalChatRecord(42, "消息", 1000, false)) }, resultCache = cache)
        coordinator.setEnabled(true)
        var status = ""
        coordinator.regenerateRecentConversation("chat") { status = it }
        runCurrent()
        assertEquals(old, cache.find(old.messageId))
        assertTrue(cache.savedMessageIds.isEmpty())
        assertTrue(status.contains("保留"))
    }

    @Test
    fun `repeated manual request shares the running batch and disabling retains old cache`() = runTest {
        val cache = RecordingAnalysisResultCache()
        val old = IpcAnalysisResult("wechat-8.0.72-42", "chat", "text", false, emotion = "平静", intents = emptyList(), riskLevel = 1, suggestion = "",
            detailIntention = "旧解读", detailContextual = true)
        cache.save(old.messageId, old)
        cache.savedMessageIds.clear()
        val analyzer = BlockingAnalyzer()
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ ->
                listOf(com.jev.relationship.ipc.LocalChatRecord(42, "消息", 1000, false)) }, resultCache = cache)
        coordinator.setEnabled(true)
        assertTrue(coordinator.regenerateRecentConversation("chat"))
        runCurrent()
        var duplicateStatus = ""
        val duplicateAccepted = coordinator.regenerateRecentConversation("chat") { duplicateStatus = it }
        coordinator.setConversationAnalysisEnabled("chat", "chat", false)
        analyzer.results.forEach { it.complete(output().let { value -> value.copy(analysis = value.analysis.copy(
            detailed = com.jev.relationship.core.model.DetailedAnalysis(intention = "新解读", contextual = true))) }) }
        runCurrent()
        assertFalse(duplicateAccepted)
        assertTrue(duplicateStatus.contains("请稍候"))
        assertEquals(1, analyzer.results.size)
        assertEquals(old, cache.find(old.messageId))
        assertTrue(cache.savedMessageIds.isEmpty())
    }

    @Test
    fun `disabling conversation stops an in flight history backfill before caching`() = runTest {
        val analyzer = BlockingAnalyzer()
        val cache = RecordingAnalysisResultCache()
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(
            capture = MessageCaptureCoordinator(), analyzer = analyzer, resultSink = sink,
            scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ ->
                listOf(com.jev.relationship.ipc.LocalChatRecord(42, "等待生成", 1000, false))
            },
            resultCache = cache,
        )
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "chat", conversationText = "等待生成",
                anchorText = "等待生成", anchorIsOutgoing = false,
                messages = listOf(VisibleChatMessage("等待生成", false, 0, localMessageId = 42)),
            ),
        )
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, analyzer.results.size)

        coordinator.setConversationAnalysisEnabled("chat", "chat", false)
        analyzer.results.single().complete(output())
        runCurrent()

        assertTrue(cache.savedMessageIds.isEmpty())
        assertTrue(sink.results.isEmpty())
    }

    @Test
    fun `live capture of a message covered by history backfill is analyzed only once`() = runTest {
        val analyzer = BlockingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val cache = RecordingAnalysisResultCache()
        val coordinator = fixture(
            capture = capture, analyzer = analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ ->
                listOf(com.jev.relationship.ipc.LocalChatRecord(42, "刚收到的消息", 1000, false))
            },
            resultCache = cache,
        )
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()
        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "chat", conversationText = "刚收到的消息",
                anchorText = "刚收到的消息", anchorIsOutgoing = false,
                messages = listOf(VisibleChatMessage("刚收到的消息", false, 0, localMessageId = 42)),
            ),
        )
        capture.submit(validMessage("chat", "刚收到的消息", 1000L).copy(messageId = "wechat-8.0.72-42"))
        advanceTimeBy(700)
        runCurrent()

        assertEquals(1, analyzer.results.size)
        analyzer.results.single().complete(output())
        runCurrent()
        assertEquals(listOf("wechat-8.0.72-42"), cache.savedMessageIds)
    }

    @Test
    fun `failed local history does not silently analyze visible fragments`() = runTest {
        val analyzer = RecordingAnalyzer()
        val coordinator = fixture(MessageCaptureCoordinator(), analyzer, scope = backgroundScope,
            localHistory = com.jev.relationship.domain.source.LocalConversationHistory { _, _ -> error("读取失败") })
        coordinator.setEnabled(true)
        coordinator.submitVisibleConversation(VisibleChatConversation("chat", "还记得吗", "还记得吗", false, listOf(VisibleChatMessage("还记得吗", false, 0))))
        advanceTimeBy(700)
        runCurrent()
        assertTrue(analyzer.calls.isEmpty())
        assertTrue(coordinator.state.value is RealtimeAnalysisState.Error)
    }

    @Test
    fun `new visible message waits without cancelling unfinished incoming analysis`() = runTest {
        val analyzer = BlockingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(capture, analyzer, resultSink = sink, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()
        val first = VisibleChatMessage("第一条", false, 0)
        val second = VisibleChatMessage("第二条", false, 0)
        fun snapshot(messages: List<VisibleChatMessage>) = VisibleChatConversation("chat", messages.joinToString("\n") { it.text }, messages.last().text, false, messages)
        coordinator.submitVisibleConversation(snapshot(listOf(first)))
        advanceTimeBy(700)
        runCurrent()
        coordinator.submitVisibleConversation(snapshot(listOf(first, second)))
        analyzer.results[0].complete(output("first"))
        runCurrent()
        assertEquals(1, sink.results.size)
        advanceTimeBy(700)
        runCurrent()
        analyzer.results[1].complete(output("second"))
        runCurrent()
        assertEquals(2, sink.results.size)
    }
    @Test
    fun `quiet window coalesces messages for one conversation`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val coordinator = fixture(capture = capture, analyzer = analyzer, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage(conversationId = "chat-a", text = "one", timestampMs = 1L))
        advanceTimeBy(500)
        capture.submit(validMessage(conversationId = "chat-a", text = "two", timestampMs = 2L))
        advanceTimeBy(699)
        assertEquals(0, analyzer.calls.size)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(1, analyzer.calls.size)
        assertEquals("对方：one\n对方：two\n\n当前待分析消息：two", analyzer.calls.single().text)
    }

    @Test
    fun `new message cancels prior generation and stale result cannot update surface`() = runTest {
        val analyzer = BlockingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val surface = AssistantSurfaceCoordinator()
        val coordinator = fixture(capture, analyzer, surface = surface, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "old", 1L))
        advanceTimeBy(700)
        runCurrent()
        capture.submit(validMessage("chat-a", "new", 2L))
        runCurrent()
        advanceTimeBy(700)
        runCurrent()

        assertEquals(2, analyzer.results.size)
        analyzer.results[0].complete(output("old result"))
        analyzer.results[1].complete(output("new result"))
        runCurrent()

        assertTrue(coordinator.state.value is RealtimeAnalysisState.Completed)
        assertEquals("new result", (coordinator.state.value as RealtimeAnalysisState.Completed).output.analysis.emotion)
        assertEquals("new result", (surface.state.value as AssistantSurfaceState.Visible).emotion)
    }

    @Test
    fun `different conversation ids keep independent quiet windows`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val coordinator = fixture(capture, analyzer, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "a", 1L))
        capture.submit(validMessage("chat-b", "b", 2L))
        advanceTimeBy(700)
        runCurrent()

        assertEquals(setOf("对方：a\n\n当前待分析消息：a", "对方：b\n\n当前待分析消息：b"), analyzer.calls.map { it.text }.toSet())
    }

    @Test
    fun `stop cancels pending work and returns to disabled`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val surface = AssistantSurfaceCoordinator().also { it.activate() }
        val coordinator = fixture(capture, analyzer, surface = surface, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "pending", 1L))
        coordinator.stop()
        advanceUntilIdle()

        assertEquals(RealtimeAnalysisState.Disabled, coordinator.state.value)
        assertEquals(0, analyzer.calls.size)
        assertEquals(AssistantSurfaceState.Hidden, surface.state.value)
    }

    @Test
    fun `history failure still shows output and completes with warning`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val history = RecordingHistoryRepository().also { it.error = IllegalStateException("database unavailable") }
        val surface = AssistantSurfaceCoordinator()
        val coordinator = fixture(capture, analyzer, history = history, surface = surface, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "hello", 1L))
        advanceTimeBy(700)
        runCurrent()

        val state = coordinator.state.value as RealtimeAnalysisState.Completed
        assertEquals(null, state.historyId)
        assertNotNull(state.historyError)
        assertEquals(AssistantSurfaceState.Visible("开心", "回应", 2), surface.state.value)
    }

    @Test
    fun `completed analysis publishes inline card for triggering message`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val store = InlineCardStore()
        val coordinator = fixture(
            capture = capture,
            analyzer = analyzer,
            inlineCardPublisher = InlineAnalysisCardPublisher(store) { 42L },
            scope = backgroundScope,
        )
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "hello", 1L))
        advanceTimeBy(700)
        runCurrent()

        assertEquals("chat-a-1", store.state.value?.anchor?.messageId)
        assertEquals("对方：hello\n\n当前待分析消息：hello", analyzer.calls.single().text)
    }

    @Test
    fun `completed analysis publishes one redacted result for triggering message`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(
            capture = capture,
            analyzer = analyzer,
            resultSink = sink,
            scope = backgroundScope,
        )
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "hello", 1L))
        advanceTimeBy(700)
        runCurrent()

        assertEquals(listOf("chat-a-1"), sink.results.map(IpcAnalysisResult::messageId))
        assertEquals(
            com.jev.relationship.domain.inline.InlineTextHasher.hash("hello"),
            sink.results.single().textHash,
        )

        coordinator.stop()

        assertEquals(1, sink.clearCount)
    }

    @Test
    fun `initial disabled settings do not clear an already connected IPC client`() = runTest {
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(
            capture = MessageCaptureCoordinator(),
            analyzer = RecordingAnalyzer(),
            resultSink = sink,
            scope = backgroundScope,
        )

        coordinator.setEnabled(false)

        assertEquals(0, sink.clearCount)
    }

    @Test
    fun `entering a chat analyzes the visible local conversation and publishes a card`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val store = InlineCardStore()
        val coordinator = fixture(
            capture = capture,
            analyzer = analyzer,
            inlineCardPublisher = InlineAnalysisCardPublisher(store) { 42L },
            scope = backgroundScope,
        )
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "文件传输助手",
                conversationText = "你好\n收到",
                anchorText = "你好",
                anchorIsOutgoing = false,
            ),
        )
        advanceTimeBy(700)
        runCurrent()

        assertEquals("你好\n收到", analyzer.calls.single().text)
        assertEquals(
            com.jev.relationship.domain.inline.InlineTextHasher.hash("你好"),
            store.state.value?.anchor?.textHash,
        )
    }

    @Test
    fun `entering a chat analyzes and publishes every visible text message`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(
            capture = capture,
            analyzer = analyzer,
            resultSink = sink,
            scope = backgroundScope,
        )
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "chat-b",
                conversationText = "你好\n收到",
                anchorText = "你好",
                anchorIsOutgoing = false,
                messages = listOf(
                    VisibleChatMessage("你好", isOutgoing = false, occurrence = 0),
                    VisibleChatMessage("收到", isOutgoing = true, occurrence = 0),
                ),
            ),
        )
        advanceTimeBy(700)
        runCurrent()

        assertEquals(1, analyzer.calls.size)
        assertEquals(1, sink.results.size)
        assertEquals("对方：你好\n\n当前待分析消息：你好", analyzer.calls.single().text)
        assertEquals(false, sink.results.single().isOutgoing)
        assertEquals(1, sink.results.map { it.messageId }.distinct().size)
    }

    @Test
    fun `scroll refresh keeps existing cards while reanalyzing the viewport`() = runTest {
        val analyzer = RecordingAnalyzer()
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(
            capture = MessageCaptureCoordinator(),
            analyzer = analyzer,
            resultSink = sink,
            scope = backgroundScope,
        )
        coordinator.setEnabled(true)
        val first = VisibleChatMessage("第一条", isOutgoing = false, occurrence = 0)
        val snapshot = VisibleChatConversation("chat", "第一条", "第一条", false, listOf(first))

        coordinator.submitVisibleConversation(snapshot)
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, sink.results.size)
        val clearCountBeforeScroll = sink.clearCount

        coordinator.invalidateVisibleViewport()

        assertEquals(clearCountBeforeScroll, sink.clearCount)
        assertEquals(1, sink.results.size)

        coordinator.submitVisibleConversation(snapshot)
        advanceTimeBy(700)
        runCurrent()
        assertEquals(2, analyzer.calls.size)
        assertEquals(2, sink.results.size)
    }

    @Test
    fun `does not publish analysis when visible messages are all outgoing`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(
            capture = capture,
            analyzer = analyzer,
            resultSink = sink,
            scope = backgroundScope,
        )
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        coordinator.submitVisibleConversation(
            VisibleChatConversation(
                conversationKey = "文件传输助手",
                conversationText = "我发出的内容",
                anchorText = "我发出的内容",
                anchorIsOutgoing = true,
                messages = listOf(
                    VisibleChatMessage("我发出的内容", isOutgoing = true, occurrence = 0),
                ),
            ),
        )
        advanceTimeBy(700)
        runCurrent()

        assertTrue(analyzer.calls.isEmpty())
        assertTrue(sink.results.isEmpty())
    }

    @Test
    fun `outgoing capture is context only and never starts analysis`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val sink = RecordingAnalysisResultSink()
        val coordinator = fixture(capture, analyzer, resultSink = sink, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()
        capture.submit(validMessage("chat-a", "自己的回复", 1L).copy(isOutgoing = true, sender = MessageSender.SELF))
        advanceTimeBy(700)
        runCurrent()
        assertTrue(analyzer.calls.isEmpty())
        assertTrue(sink.results.isEmpty())
        assertEquals("自己的回复", capture.latestConversation("chat-a")?.text)
    }

    @Test
    fun `own reply during quiet window cannot become the analysis target`() = runTest {
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val coordinator = fixture(capture, analyzer, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()
        capture.submit(validMessage("chat-a", "你记得吗", 1L))
        runCurrent()
        capture.submit(validMessage("chat-a", "我记得", 2L).copy(isOutgoing = true, sender = MessageSender.SELF))
        advanceTimeBy(700)
        runCurrent()
        assertEquals(listOf("对方：你记得吗\n\n当前待分析消息：你记得吗"), analyzer.calls.map { it.text })
    }

    @Test
    fun `analyzer failure enters error and later messages can retry`() = runTest {
        val analyzer = RecordingAnalyzer().apply {
            failures += IllegalStateException("temporary failure")
        }
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val coordinator = fixture(capture, analyzer, scope = backgroundScope)
        coordinator.setEnabled(true)
        coordinator.start()
        runCurrent()

        capture.submit(validMessage("chat-a", "first", 1L))
        advanceTimeBy(700)
        runCurrent()
        assertTrue(coordinator.state.value is RealtimeAnalysisState.Error)

        capture.submit(validMessage("chat-a", "second", 2L))
        advanceTimeBy(700)
        runCurrent()

        assertTrue(coordinator.state.value is RealtimeAnalysisState.Completed)
        assertEquals(2, analyzer.calls.size)
    }

    private fun fixture(
        capture: MessageCaptureCoordinator,
        analyzer: ConversationAnalyzer,
        history: RecordingHistoryRepository = RecordingHistoryRepository(),
        surface: AssistantSurfaceCoordinator = AssistantSurfaceCoordinator(),
        inlineCardPublisher: InlineAnalysisCardPublisher? = null,
        resultSink: IpcAnalysisResultSink? = null,
        scope: CoroutineScope,
        localHistory: com.jev.relationship.domain.source.LocalConversationHistory? = null,
        resultCache: AnalysisResultCache? = null,
    ) = RealtimeAnalysisCoordinator(
        captureCoordinator = capture,
        analyzer = analyzer,
        historyRepository = history,
        surfaceCoordinator = surface,
            settingsRepository = AlwaysEnabledSettingsRepository,
            scope = scope,
            inlineCardPublisher = inlineCardPublisher,
            analysisResultSink = resultSink,
            analysisResultCache = resultCache,
            localHistory = localHistory,
        )

    private object AlwaysEnabledSettingsRepository : RealtimeAssistantSettingsRepository {
        override val settings = flowOf(RealtimeAssistantSettings(enabled = true))

        override suspend fun setEnabled(enabled: Boolean) = Unit
    }

    private fun validMessage(conversationId: String, text: String, timestampMs: Long) = CapturedMessage(
        conversationId = conversationId,
        sender = MessageSender.CONTACT,
        text = text,
        timestampMs = timestampMs,
        sourcePackage = IpcProtocol.WECHAT_PACKAGE,
        sourceClass = null,
        isOutgoing = false,
        messageId = "$conversationId-$timestampMs",
    )

    private companion object {
        fun output(emotion: String = "开心") = AnalysisOutput(
            analysis = AnalysisResult(
                emotion = emotion,
                intents = listOf(IntentProbability("回应", 0.9)),
                riskLevel = 2,
                suggestion = "先回应",
            ),
            replies = listOf(ReplySuggestion(ReplyTone.Gentle, "我在听")),
        )
    }

    private class RecordingAnalyzer : ConversationAnalyzer {
        val calls = mutableListOf<Conversation>()
        val failures = ArrayDeque<Throwable>()

        override suspend fun invoke(conversation: Conversation): AnalysisOutput {
            calls += conversation
            if (failures.isNotEmpty()) throw failures.removeFirst()
            return output()
        }
    }

    private class BlockingAnalyzer : ConversationAnalyzer {
        val results = mutableListOf<CompletableDeferred<AnalysisOutput>>()

        override suspend fun invoke(conversation: Conversation): AnalysisOutput {
            return withContext(NonCancellable) {
                CompletableDeferred<AnalysisOutput>().also { results += it }.await()
            }
        }
    }

    private class RecordingHistoryRepository : HistoryRepository {
        var error: Throwable? = null

        override suspend fun save(conversation: Conversation, output: AnalysisOutput, createdAt: Long): Long {
            error?.let { throw it }
            return 42L
        }

        override fun observeAll(): Flow<List<SavedAnalysis>> = emptyFlow()

        override suspend fun findById(id: Long): SavedAnalysis? = null

        override suspend fun delete(id: Long) = Unit
    }

    private class RecordingAnalysisResultSink : IpcAnalysisResultSink {
        val batches = mutableListOf<List<IpcAnalysisResult>>()
        override fun publishAll(results: List<IpcAnalysisResult>) {
            batches += results
            results.forEach(::publish)
        }
        val replySuggestions = mutableListOf<com.jev.relationship.ipc.IpcReplySuggestion>()
        override fun publishReplySuggestion(suggestion: com.jev.relationship.ipc.IpcReplySuggestion) { replySuggestions += suggestion }
        val results = mutableListOf<IpcAnalysisResult>()
        var clearCount = 0

        override fun publish(result: IpcAnalysisResult) {
            results += result
        }

        override fun clear() {
            clearCount += 1
        }
    }

    private class RecordingAnalysisResultCache : AnalysisResultCache {
        private val values = mutableMapOf<String, IpcAnalysisResult>()
        var findCount = 0
        var batchFindCount = 0
        val savedMessageIds = mutableListOf<String>()

        override suspend fun findAll(messageIds: Collection<String>): Map<String, IpcAnalysisResult> {
            batchFindCount++
            return messageIds.mapNotNull { id -> values[id]?.let { id to it } }.toMap()
        }

        override suspend fun find(messageId: String): IpcAnalysisResult? {
            findCount++
            return values[messageId]
        }

        override suspend fun save(messageId: String, result: IpcAnalysisResult) {
            values[messageId] = result
            savedMessageIds += messageId
        }
    }
}
