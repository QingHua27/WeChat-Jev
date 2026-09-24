package com.jev.relationship.ipc

import android.os.Message
import android.os.Messenger
import com.jev.relationship.domain.source.LocalConversationHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Uses only the authenticated WeChat client; never falls back to visible fragments. */
class LocalHistoryBroker : LocalConversationHistory {
    private val client = MutableStateFlow<Messenger?>(null)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<LocalHistoryPage>>()

    fun attach(target: Messenger, capabilities: Set<String>) {
        disconnect()
        if (IpcCapabilities.LOCAL_HISTORY in capabilities) client.value = target
    }

    fun disconnect() {
        client.value = null
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(IllegalStateException("微信本地记录连接已断开")) }
    }

    fun receive(page: LocalHistoryPage) { pending.remove(page.requestId)?.complete(page) }

    override suspend fun load(conversationId: String, title: String): List<LocalChatRecord> {
        val target = withTimeout(8_000) { client.filterNotNull().first() }
        var request = LocalHistoryRequest(UUID.randomUUID().toString(), conversationId, title)
        val records = mutableListOf<LocalChatRecord>()
        var chars = 0
        var expectedTalker = conversationId
        do {
            check(client.value === target) { "微信连接已改变" }
            val response = CompletableDeferred<LocalHistoryPage>()
            pending[request.requestId] = response
            val page = try {
                target.send(Message.obtain(null, IpcProtocol.MSG_HISTORY_REQUEST).apply { data = request.toBundle() })
                withTimeout(10_000) { response.await() }
            } finally { pending.remove(request.requestId) }
            check(page.error == null) { page.error.orEmpty() }
            if (expectedTalker.isEmpty()) expectedTalker = page.conversationId
            check(expectedTalker.isNotBlank() && page.conversationId == expectedTalker) { "聊天对象已改变" }
            check(request.upperId == 0L || request.upperId == page.upperId) { "本地记录快照已改变" }
            records += page.records
            chars += page.records.sumOf { it.text.length }
            check(chars <= 2_000_000 && records.size <= 100_000) { "本地对话超过本次完整分析容量，未截断或分析部分记录" }
            if (!page.complete) check(page.afterTime > request.afterTime || (page.afterTime == request.afterTime && page.afterId > request.afterId)) { "本地记录分页未前进" }
            request = request.copy(requestId = UUID.randomUUID().toString(), conversationId = expectedTalker, afterTime = page.afterTime, afterId = page.afterId, upperId = page.upperId)
        } while (!page.complete)
        check(records.map { it.id }.distinct().size == records.size) { "本地记录包含重复消息标识" }
        android.util.Log.i("JevLocalHistory", "complete records=${records.size} chars=$chars")
        return records
    }
}
