package com.jev.relationship.domain.realtime

import com.jev.relationship.ipc.IpcAnalysisResultSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.jev.relationship.domain.inline.InlineTextHasher
import com.jev.relationship.ipc.IpcProtocol

class ConversationCachePreloader(
    private val cache: AnalysisResultCache?,
    private val sink: IpcAnalysisResultSink?,
    private val scope: CoroutineScope,
) {
    private var key: Pair<String, Boolean>? = null
    private var job: Job? = null

    fun preload(conversationId: String, fast: Boolean) {
        val next = conversationId to fast
        if (key == next) return
        cancel()
        key = next
        sink?.setFastCacheDisplay(fast)
        if (!fast || conversationId.isBlank() || cache == null || sink == null) return
        job = scope.launch {
            try {
                cache.conversationResults(InlineTextHasher.hash(conversationId)).collect { page ->
                    for (batch in page.chunked(IpcProtocol.MAX_BATCH_SIZE)) {
                        if (key != next) return@collect
                        sink.publishAll(batch)
                        // Leave time for the current viewport and Binder receiver.
                        delay(16)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (key == next) key = null // A later snapshot may retry a transient DB error.
                runCatching { android.util.Log.w("JevCachePreload", "preload failed type=${error.javaClass.simpleName}") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        key = null
    }
}
