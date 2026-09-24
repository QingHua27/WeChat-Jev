package com.jev.relationship.domain.chatassistant

import com.jev.relationship.data.remote.ChatAssistantCompletion
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.source.LocalConversationHistory
import com.jev.relationship.ipc.ChatAssistantRequest
import com.jev.relationship.ipc.ChatAssistantResult
import com.jev.relationship.ipc.ChatAssistantTurn
import com.jev.relationship.ipc.IpcProtocol
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

class ChatAssistantCoordinator(
    private val localHistory: LocalConversationHistory,
    private val store: ChatAssistantStore,
    private val settingsRepository: SettingsRepository,
    private val completion: ChatAssistantCompletion,
) {
    suspend fun handle(request: ChatAssistantRequest, onPartial: (ChatAssistantResult) -> Unit = {}): ChatAssistantResult =
        withTimeoutOrNull(IpcProtocol.CHAT_ASSISTANT_TIMEOUT_MS - 10_000L) {
            handleWithinDeadline(request, onPartial)
        } ?: ChatAssistantResult(
            requestId = request.requestId,
            conversationId = request.conversationId,
            turns = emptyList(),
            error = "分析处理超时，请检查模型服务，并在系统耗电管理中允许 Jev 后台运行后重试。",
        )

    private suspend fun handleWithinDeadline(request: ChatAssistantRequest, onPartial: (ChatAssistantResult) -> Unit): ChatAssistantResult {
        require(request.conversationId.isNotBlank()) { "聊天对象标识无效" }
        val previous = inStage(ChatAssistantFailure.Stage.SESSION_STORAGE) {
            store.load(request.conversationId)
        }
        if (request.resumeSession && request.question == null && !previous?.turns.isNullOrEmpty()) {
            return ChatAssistantResult.forDisplay(
                requestId = request.requestId,
                conversationId = request.conversationId,
                turns = previous!!.turns.map { ChatAssistantTurn(it.role, it.content, it.createdAtMs) },
            )
        }
        val transcript = inStage(ChatAssistantFailure.Stage.LOCAL_HISTORY) {
            localHistory.load(request.conversationId, request.title).also {
                check(it.isNotEmpty()) { "当前聊天没有可分析的本地文本记录" }
            }
        }

        val refreshReport = request.question == null
        val messages = ChatAssistantPromptBuilder.build(
            conversationId = request.conversationId,
            transcript = transcript,
            turns = previous?.turns.orEmpty(),
            question = request.question,
            refreshReport = refreshReport,
            fullContext = request.fullContext,
        )
        val settings = inStage(ChatAssistantFailure.Stage.MODEL_CONFIGURATION) {
            settingsRepository.currentProviderSettings().replySettings()
        }
        val answer = inStage(ChatAssistantFailure.Stage.MODEL_REQUEST) {
            val requestContext = currentCoroutineContext()
            completion.stream(settings, messages) { text ->
                requestContext.ensureActive()
                require(text.length <= ChatAssistantTurn.MAX_CONTENT_LENGTH) { "理解模型回复超出保存容量，本次问答未保存" }
                if (text.isNotBlank()) onPartial(ChatAssistantResult(
                    requestId = request.requestId,
                    conversationId = request.conversationId,
                    turns = listOf(ChatAssistantTurn(ChatAssistantTurn.ROLE_ASSISTANT, text, System.currentTimeMillis())),
                    isPartial = true,
                ))
            }.also {
                require(it.isNotBlank() && it.length <= ChatAssistantTurn.MAX_CONTENT_LENGTH) {
                    "理解模型回复超出保存容量，本次问答未保存"
                }
            }
        }
        val updated = inStage(ChatAssistantFailure.Stage.SESSION_STORAGE) {
            if (refreshReport) {
                store.saveReport(request.conversationId, request.title,
                    if (request.fullContext) REPORT_REQUEST else ChatAssistantRequest.SAVING_ANALYSIS_PROMPT, answer)
            } else {
                store.appendExchange(request.conversationId, request.title, request.question!!, answer)
            }
            checkNotNull(store.load(request.conversationId)) { "问答记录保存失败" }
        }
        return ChatAssistantResult.forDisplay(
            requestId = request.requestId,
            conversationId = request.conversationId,
            turns = updated.turns.map { ChatAssistantTurn(it.role, it.content, it.createdAtMs) },
        )
    }

    private suspend fun <T> inStage(stage: ChatAssistantFailure.Stage, block: suspend () -> T): T = try {
        block()
    } catch (timeout: TimeoutCancellationException) {
        // A dependency's timeout is a reportable failure. Cancellation of this
        // request (including its overall deadline) must still propagate.
        currentCoroutineContext().ensureActive()
        throw ChatAssistantFailure(stage, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ChatAssistantFailure) {
        throw failure
    } catch (failure: Throwable) {
        throw ChatAssistantFailure(stage, failure)
    }

    private companion object {
        const val REPORT_REQUEST = ChatAssistantPromptBuilder.REPORT_REQUEST
    }
}
