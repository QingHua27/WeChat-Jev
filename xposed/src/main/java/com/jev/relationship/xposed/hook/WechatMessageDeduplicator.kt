package com.jev.relationship.xposed.hook

import com.jev.relationship.ipc.IpcProtocol

class WechatMessageDeduplicator(
    private val capacity: Int = IpcProtocol.MAX_BATCH_SIZE * 8,
) {
    private val seenMessageIds = LinkedHashSet<String>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun shouldEmit(messageId: String): Boolean {
        if (!seenMessageIds.add(messageId)) return false
        if (seenMessageIds.size > capacity) {
            val oldest = seenMessageIds.iterator().next()
            seenMessageIds.remove(oldest)
        }
        return true
    }
}
