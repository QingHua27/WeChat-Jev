package com.jev.relationship.domain.source

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.ipc.LocalChatRecord
import com.jev.relationship.ipc.ChatTextPolicy

fun interface LocalConversationHistory {
    suspend fun load(conversationId: String, title: String): List<LocalChatRecord>
}

object LocalHistoryContext {
    const val CONTEXT_MESSAGE_LIMIT = 60
    fun forVisible(
        history: List<LocalChatRecord>,
        visible: List<VisibleChatMessage>,
        isGroupChat: Boolean = false,
    ): List<Conversation> {
        return matchVisibleIndices(history, visible, isGroupChat).map { index -> through(history, index) }
    }

    fun matchVisibleRecords(
        history: List<LocalChatRecord>,
        visible: List<VisibleChatMessage>,
        isGroupChat: Boolean = false,
    ): List<LocalChatRecord> {
        return matchVisibleIndices(history, visible, isGroupChat).map(history::get)
    }

    private fun matchVisibleIndices(
        history: List<LocalChatRecord>,
        visible: List<VisibleChatMessage>,
        isGroupChat: Boolean,
    ): List<Int> {
        check(visible.isNotEmpty()) { "没有可定位的文本消息" }
        if (visible.all { it.localMessageId != null }) {
            return visible.map { message ->
                val matches = history.indices.filter { index ->
                    val record = history[index]
                    record.id == message.localMessageId && record.isOutgoing == message.isOutgoing &&
                        matchesText(record.text, message.text, isGroupChat)
                }
                check(matches.size == 1) { "本地消息标识与当前气泡不一致，已停止分析以避免错贴" }
                matches.single()
            }
        }
        val starts = history.indices.filter { start ->
            start + visible.size <= history.size && visible.indices.all { offset ->
                val record = history[start + offset]
                record.isOutgoing == visible[offset].isOutgoing &&
                    matchesText(record.text, visible[offset].text, isGroupChat)
            }
        }
        check(starts.size == 1) { "本地记录与当前消息无法唯一对应，已停止分析以避免错贴" }
        return visible.indices.map { starts.single() + it }
    }

    fun forCaptured(history: List<LocalChatRecord>, messageId: String?, text: String): Conversation {
        val id = messageId?.removePrefix("wechat-8.0.72-")?.toLongOrNull()
        val index = history.indexOfFirst { it.id == id && !it.isOutgoing && normalize(it.text) == normalize(text) }
        check(index >= 0) { "待分析消息已撤回或不在本地记录中" }
        return through(history, index)
    }

    fun forRecord(history: List<LocalChatRecord>, index: Int): Conversation {
        require(index in history.indices)
        return through(history, index)
    }

    private fun through(history: List<LocalChatRecord>, index: Int): Conversation = Conversation(
        history.subList((index + 1 - CONTEXT_MESSAGE_LIMIT).coerceAtLeast(0), index + 1)
            .filter { ChatTextPolicy.isDialogue(it.text) }.joinToString("\n") {
            (if (it.isOutgoing) "我：" else "对方：") + it.text
        } + "\n\n当前待分析消息：" + history[index].text,
        hasBoundedMessageContext = true,
    )
    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    private fun matchesText(recordText: String, visibleText: String, isGroupChat: Boolean): Boolean {
        val stored = normalize(recordText)
        val shown = normalize(visibleText)
        return stored == shown || (isGroupChat && stored.endsWith(" $shown"))
    }
}
