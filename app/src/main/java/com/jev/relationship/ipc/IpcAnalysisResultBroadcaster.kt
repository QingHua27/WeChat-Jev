package com.jev.relationship.ipc

import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IpcAnalysisResultSink {
    fun publish(result: IpcAnalysisResult)
    fun clear()
    fun publishReplySuggestion(suggestion: IpcReplySuggestion) = Unit
}

class IpcAnalysisResultBroadcaster(
    private val sendMessage: (Messenger, Message) -> Unit = { target, message ->
        target.send(message)
    },
) : IpcAnalysisResultSink {
    private val _embeddedClientActive = MutableStateFlow(false)
    private var client: Messenger? = null
    private val pendingResults = ArrayDeque<IpcAnalysisResult>()

    val embeddedClientActive: StateFlow<Boolean> = _embeddedClientActive.asStateFlow()

    @Synchronized
    fun setClient(target: Messenger, capabilities: Set<String>): Boolean {
        if (IpcCapabilities.EMBEDDED_CHAT_CARD !in capabilities) {
            clearClient()
            return false
        }
        client = target
        _embeddedClientActive.value = true
        flushPending(target)
        return true
    }

    @Synchronized
    override fun clear() {
        pendingResults.clear()
        client?.let { target ->
            runCatching { sendMessage(target, Message.obtain(null, IpcProtocol.MSG_ANALYSIS_CLEAR)) }
                .onFailure { clearClient() }
        }
    }

    @Synchronized
    fun clearClient() {
        client = null
        _embeddedClientActive.value = false
    }

    @Synchronized
    override fun publishReplySuggestion(suggestion: IpcReplySuggestion) {
        client?.let { target ->
            runCatching { sendMessage(target, Message.obtain(null, IpcProtocol.MSG_REPLY_SUGGESTION).apply {
                data = suggestion.toBundle()
            }) }.onFailure { clearClient() }
        }
    }

    @Synchronized
    override fun publish(result: IpcAnalysisResult) {
        val target = client ?: run {
            enqueuePending(result)
            return
        }
        send(target, result)
    }

    private fun send(target: Messenger, result: IpcAnalysisResult) {
        val message = Message.obtain(null, IpcProtocol.MSG_ANALYSIS_RESULT).apply {
            data = IpcCodec.encodeAnalysisResult(result)
        }
        runCatching { sendMessage(target, message) }
            .onFailure { error ->
                if (error is RemoteException || error is RuntimeException) {
                    enqueuePending(result)
                    clearClient()
                }
            }
    }

    private fun flushPending(target: Messenger) {
        while (pendingResults.isNotEmpty()) {
            val result = pendingResults.removeFirst()
            val sent = runCatching {
                val message = Message.obtain(null, IpcProtocol.MSG_ANALYSIS_RESULT).apply {
                    data = IpcCodec.encodeAnalysisResult(result)
                }
                sendMessage(target, message)
                true
            }.getOrElse { error ->
                if (error is RemoteException || error is RuntimeException) {
                    pendingResults.addFirst(result)
                    clearClient()
                }
                false
            }
            if (!sent) return
        }
    }

    private fun enqueuePending(result: IpcAnalysisResult) {
        pendingResults.addLast(result)
        while (pendingResults.size > IpcProtocol.MAX_BATCH_SIZE) pendingResults.removeFirst()
    }

}
