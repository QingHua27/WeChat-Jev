package com.jev.relationship.domain.realtime

import com.jev.relationship.domain.HistoryRepository
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.ipc.MessageCaptureCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class RealtimeAnalysisCoordinator(
    private val captureCoordinator: MessageCaptureCoordinator,
    private val analyzer: ConversationAnalyzer,
    private val historyRepository: HistoryRepository,
    private val surfaceCoordinator: AssistantSurfaceCoordinator,
    private val scope: CoroutineScope,
) {
    companion object {
        const val QUIET_WINDOW_MS = 700L
    }

    private val _state = MutableStateFlow<RealtimeAnalysisState>(RealtimeAnalysisState.Disabled)
    private val quietJobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private var collectorJob: Job? = null
    private var enabled = false

    val state: StateFlow<RealtimeAnalysisState> = _state.asStateFlow()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            stop()
        } else if (collectorJob?.isActive != true) {
            _state.value = RealtimeAnalysisState.WaitingForPermission
        }
    }

    fun start() {
        if (!enabled || collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            captureCoordinator.events.collect { message ->
                if (!enabled) return@collect
                val conversationId = message.conversationId
                _state.value = RealtimeAnalysisState.Collecting(conversationId)
                quietJobs.remove(conversationId)?.cancel()
                val generation = (generations[conversationId] ?: 0L) + 1L
                generations[conversationId] = generation
                quietJobs[conversationId] = launchAnalysis(conversationId, generation)
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        quietJobs.values.forEach(Job::cancel)
        quietJobs.clear()
        generations.clear()
        surfaceCoordinator.hide()
        _state.value = RealtimeAnalysisState.Disabled
    }

    private fun launchAnalysis(conversationId: String, generation: Long): Job = scope.launch {
        try {
            delay(QUIET_WINDOW_MS)
            if (!isCurrent(conversationId, generation)) return@launch
            val conversation = captureCoordinator.latestConversation(conversationId) ?: return@launch
            _state.value = RealtimeAnalysisState.Analyzing(conversationId, generation)
            val output = analyzer(conversation)
            if (!isCurrent(conversationId, generation)) return@launch

            var historyId: Long? = null
            var historyError: String? = null
            try {
                historyId = historyRepository.save(conversation, output)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                historyError = error.message ?: error::class.simpleName ?: "history save failed"
            }
            if (!isCurrent(conversationId, generation)) return@launch

            surfaceCoordinator.show(output.analysis)
            _state.value = RealtimeAnalysisState.Completed(
                conversationId = conversationId,
                output = output,
                historyId = historyId,
                historyError = historyError,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(conversationId, generation)) {
                _state.value = RealtimeAnalysisState.Error(
                    conversationId = conversationId,
                    message = error.message ?: error::class.simpleName ?: "analysis failed",
                )
            }
        } finally {
            if (quietJobs[conversationId] === coroutineContext[Job]) {
                quietJobs.remove(conversationId)
            }
        }
    }

    private suspend fun isCurrent(conversationId: String, generation: Long): Boolean =
        enabled && coroutineContext.isActive && generations[conversationId] == generation
}
