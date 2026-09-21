package com.jev.relationship.domain.realtime

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.SavedAnalysis
import com.jev.relationship.core.model.ReplyTone
import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.domain.HistoryRepository
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.domain.surface.AssistantSurfaceState
import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.IpcProtocol
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
        assertEquals("one\ntwo", analyzer.calls.single().text)
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

        assertEquals(setOf("a", "b"), analyzer.calls.map { it.text }.toSet())
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
        scope: CoroutineScope,
    ) = RealtimeAnalysisCoordinator(
        captureCoordinator = capture,
        analyzer = analyzer,
        historyRepository = history,
        surfaceCoordinator = surface,
        settingsRepository = AlwaysEnabledSettingsRepository,
        scope = scope,
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
}
