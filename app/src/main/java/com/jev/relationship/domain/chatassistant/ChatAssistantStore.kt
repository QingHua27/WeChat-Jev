package com.jev.relationship.domain.chatassistant

data class ChatAssistantConversationTurn(
    val role: String,
    val content: String,
    val createdAtMs: Long,
)

data class ChatAssistantSession(
    val conversationId: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val turns: List<ChatAssistantConversationTurn>,
)

interface ChatAssistantStore {
    suspend fun load(conversationId: String): ChatAssistantSession?

    suspend fun saveReport(conversationId: String, title: String, question: String, assistantText: String)

    suspend fun appendExchange(conversationId: String, title: String, question: String, answer: String)
}
