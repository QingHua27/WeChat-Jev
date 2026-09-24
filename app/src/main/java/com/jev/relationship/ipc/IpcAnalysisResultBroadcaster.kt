package com.jev.relationship.ipc

import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IpcAnalysisResultSink {
    fun setFastCacheDisplay(enabled: Boolean) = Unit
    fun publish(result: IpcAnalysisResult)
    fun publishAll(results: List<IpcAnalysisResult>) = results.forEach(::publish)
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
    private var batchCapable = false
    private var fastCacheDisplay = true
    private var cachePreloadCapable = false
    private val pendingResults = ArrayDeque<IpcAnalysisResult>()

    val embeddedClientActive: StateFlow<Boolean> = _embeddedClientActive.asStateFlow()

    @Synchronized
    fun setClient(target: Messenger, capabilities: Set<String>): Boolean {
        if (IpcCapabilities.EMBEDDED_CHAT_CARD !in capabilities) {
            clearClient()
            return false
        }
        client = target
        batchCapable = IpcCapabilities.ANALYSIS_RESULT_BATCH in capabilities
        cachePreloadCapable = IpcCapabilities.CACHE_PRELOAD in capabilities
        _embeddedClientActive.value = true
        sendCacheDisplayMode(target)
        flushPending(target)
        return true
    }

    @Synchronized
    override fun setFastCacheDisplay(enabled: Boolean) {
        if (fastCacheDisplay == enabled) return
        fastCacheDisplay = enabled
        client?.let(::sendCacheDisplayMode)
    }

    private fun sendCacheDisplayMode(target: Messenger) {
        if (!cachePreloadCapable) return
        runCatching {
            sendMessage(target, Message.obtain(null, IpcProtocol.MSG_CACHE_DISPLAY_MODE).apply {
                data = android.os.Bundle().apply { putBoolean(IpcProtocol.KEY_FAST_CACHE_DISPLAY, fastCacheDisplay) }
            })
        }.onFailure { clearClient() }
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
        batchCapable = false
        cachePreloadCapable = false
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

    @Synchronized
    override fun publishAll(results: List<IpcAnalysisResult>) {
        if (!batchCapable) {
            results.forEach(::publish)
            return
        }
        // Bound the Binder payload even when a caller restores a whole conversation.
        results.chunked(IpcProtocol.MAX_BATCH_SIZE).forEach { batch ->
            val target = client
            if (target == null) batch.forEach(::enqueuePending)
            else sendBatch(target, batch)
        }
    }

    private fun sendBatch(target: Messenger, results: List<IpcAnalysisResult>): Boolean = runCatching {
        sendMessage(target, Message.obtain(null, IpcProtocol.MSG_ANALYSIS_BATCH).apply {
            data = IpcCodec.encodeAnalysisResults(results)
        })
        true
    }.getOrElse {
        results.forEach(::enqueuePending)
        clearClient()
        false
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
        if (batchCapable && pendingResults.isNotEmpty()) {
            val batch = pendingResults.toList()
            pendingResults.clear()
            sendBatch(target, batch)
            return
        }
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
