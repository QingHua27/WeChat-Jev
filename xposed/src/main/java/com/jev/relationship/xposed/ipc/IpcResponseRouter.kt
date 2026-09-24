package com.jev.relationship.xposed.ipc

import android.os.Message
import com.jev.relationship.ipc.HandshakeResult
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcCodec
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.ChatAssistantResult

class IpcResponseRouter(
    private val onHandshake: (HandshakeResult) -> Unit,
    private val onAnalysisResult: (IpcAnalysisResult) -> Unit,
    private val onAnalysisCleared: () -> Unit = {},
    private val onAnalysisResults: (List<IpcAnalysisResult>) -> Unit = { it.forEach(onAnalysisResult) },
    private val onFastCacheDisplay: (Boolean) -> Unit = {},
) {
    private data class PendingAssistantRequest(
        val conversationId: String,
        val callback: (ChatAssistantResult) -> Unit,
    )

    private val pendingAssistantRequests = linkedMapOf<String, PendingAssistantRequest>()

    fun registerAssistantRequest(
        requestId: String,
        conversationId: String,
        callback: (ChatAssistantResult) -> Unit,
    ) {
        require(requestId.isNotBlank() && conversationId.isNotBlank())
        check(pendingAssistantRequests.size < MAX_PENDING_ASSISTANT_REQUESTS) { "Too many pending assistant requests" }
        pendingAssistantRequests[requestId] = PendingAssistantRequest(conversationId, callback)
    }

    fun cancelAssistantRequest(requestId: String): Boolean = pendingAssistantRequests.remove(requestId) != null

    fun failAssistantRequest(requestId: String, error: String): Boolean {
        val request = pendingAssistantRequests.remove(requestId) ?: return false
        request.callback(ChatAssistantResult(requestId, request.conversationId, emptyList(), error.take(256)))
        return true
    }

    fun failAssistantRequests(error: String) {
        val pending = pendingAssistantRequests.toList()
        pendingAssistantRequests.clear()
        pending.forEach { (requestId, request) ->
            request.callback(ChatAssistantResult(requestId, request.conversationId, emptyList(), error.take(256)))
        }
    }

    fun handle(message: Message): Boolean = when (message.what) {
        IpcProtocol.MSG_CACHE_DISPLAY_MODE -> {
            if (message.data.containsKey(IpcProtocol.KEY_FAST_CACHE_DISPLAY)) {
                onFastCacheDisplay(message.data.getBoolean(IpcProtocol.KEY_FAST_CACHE_DISPLAY))
            }
            true
        }
        IpcProtocol.MSG_ANALYSIS_BATCH -> {
            runCatching { IpcCodec.decodeAnalysisResults(message.data) }
                .onSuccess(onAnalysisResults)
            true
        }
        IpcProtocol.MSG_ANALYSIS_CLEAR -> {
            onAnalysisCleared()
            true
        }
        IpcProtocol.MSG_HANDSHAKE_RESULT -> {
            runCatching { IpcCodec.decodeHandshakeResult(message.data) }
                .onSuccess(onHandshake)
            true
        }
        IpcProtocol.MSG_ANALYSIS_RESULT -> {
            runCatching { IpcCodec.decodeAnalysisResult(message.data) }
                .onSuccess(onAnalysisResult)
            true
        }
        IpcProtocol.MSG_CHAT_ASSISTANT_RESULT, IpcProtocol.MSG_CHAT_ASSISTANT_PROGRESS -> {
            runCatching { ChatAssistantResult.fromBundle(message.data) }
                .onSuccess { result ->
                    if (result.isPartial != (message.what == IpcProtocol.MSG_CHAT_ASSISTANT_PROGRESS)) return@onSuccess
                    val pending = pendingAssistantRequests[result.requestId] ?: return@onSuccess
                    if (pending.conversationId != result.conversationId) return@onSuccess
                    if (!result.isPartial) pendingAssistantRequests.remove(result.requestId)
                    pending.callback(result)
                }
            true
        }
        else -> false
    }

    private companion object {
        const val MAX_PENDING_ASSISTANT_REQUESTS = 4
    }
}
