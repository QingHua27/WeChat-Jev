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
import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.MessageCaptureCoordinator
import com.jev.relationship.ipc.MessageSender
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeAnalysisCoordinatorSettingsTest {
    @Test
    fun `disabled settings suppress analysis after start`() = runTest {
        val settings = FakeSettingsRepository()
        val analyzer = RecordingAnalyzer()
        val capture = MessageCaptureCoordinator().also { it.activate() }
        val coordinator = RealtimeAnalysisCoordinator(
            captureCoordinator = capture,
            analyzer = analyzer,
            historyRepository = NoOpHistoryRepository,
            surfaceCoordinator = AssistantSurfaceCoordinator(),
            settingsRepository = settings,
            scope = backgroundScope,
        )
        coordinator.start()
        runCurrent()

        capture.submit(message("disabled", 1L))
        advanceTimeBy(1_000L)
        assertEquals(0, analyzer.calls)

        settings.setEnabled(true)
        runCurrent()
        capture.submit(message("enabled", 2L))
        advanceTimeBy(700L)
        runCurrent()

        assertEquals(1, analyzer.calls)
        assertTrue(coordinator.state.value is RealtimeAnalysisState.Completed)
    }

    private class FakeSettingsRepository : RealtimeAssistantSettingsRepository {
        private val state = MutableStateFlow(RealtimeAssistantSettings())
        override val settings: Flow<RealtimeAssistantSettings> = state

        override suspend fun setEnabled(enabled: Boolean) {
            state.value = RealtimeAssistantSettings(enabled)
        }
    }

    private class RecordingAnalyzer : ConversationAnalyzer {
        var calls = 0

        override suspend fun invoke(conversation: Conversation): AnalysisOutput {
            calls += 1
            return AnalysisOutput(
                analysis = AnalysisResult("开心", listOf(IntentProbability("回应", 0.9)), 1, "先回应"),
                replies = listOf(ReplySuggestion(ReplyTone.Gentle, "我在听")),
            )
        }
    }

    private object NoOpHistoryRepository : HistoryRepository {
        override suspend fun save(conversation: Conversation, output: AnalysisOutput, createdAt: Long): Long = 1L
        override fun observeAll(): Flow<List<SavedAnalysis>> = emptyFlow()
        override suspend fun findById(id: Long): SavedAnalysis? = null
        override suspend fun delete(id: Long) = Unit
    }

    private fun message(text: String, timestampMs: Long) = CapturedMessage(
        conversationId = "chat-1",
        sender = MessageSender.CONTACT,
        text = text,
        timestampMs = timestampMs,
        sourcePackage = IpcProtocol.WECHAT_PACKAGE,
        sourceClass = null,
        isOutgoing = false,
        messageId = "message-$timestampMs",
    )
}
