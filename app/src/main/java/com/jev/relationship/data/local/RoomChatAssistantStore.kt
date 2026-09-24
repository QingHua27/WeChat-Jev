package com.jev.relationship.data.local

import androidx.room.withTransaction
import com.jev.relationship.domain.chatassistant.ChatAssistantConversationTurn
import com.jev.relationship.domain.chatassistant.ChatAssistantSession
import com.jev.relationship.domain.chatassistant.ChatAssistantStore

class RoomChatAssistantStore(
    private val database: JevDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatAssistantStore {
    private val dao = database.chatAssistantDao()

    override suspend fun load(conversationId: String): ChatAssistantSession? {
        val session = dao.findSession(conversationId) ?: return null
        return ChatAssistantSession(
            conversationId = session.conversationId,
            title = session.title,
            createdAtMs = session.createdAtMs,
            updatedAtMs = session.updatedAtMs,
            turns = dao.findTurns(conversationId).map {
                ChatAssistantConversationTurn(it.role, it.content, it.createdAtMs)
            },
        )
    }

    override suspend fun saveReport(conversationId: String, title: String, question: String, assistantText: String) {
        require(conversationId.isNotBlank() && question.isNotBlank() && assistantText.isNotBlank())
        val now = clock()
        database.withTransaction {
            ensureSession(conversationId, title, now)
            dao.insertTurn(ChatAssistantTurnEntity(conversationId = conversationId, role = "user", content = question, createdAtMs = now))
            dao.insertTurn(ChatAssistantTurnEntity(conversationId = conversationId, role = "assistant", content = assistantText, createdAtMs = now))
        }
    }

    override suspend fun appendExchange(conversationId: String, title: String, question: String, answer: String) {
        require(conversationId.isNotBlank() && question.isNotBlank() && answer.isNotBlank())
        val now = clock()
        database.withTransaction {
            ensureSession(conversationId, title, now)
            dao.insertTurn(ChatAssistantTurnEntity(conversationId = conversationId, role = "user", content = question, createdAtMs = now))
            dao.insertTurn(ChatAssistantTurnEntity(conversationId = conversationId, role = "assistant", content = answer, createdAtMs = now))
        }
    }

    private suspend fun ensureSession(conversationId: String, title: String, now: Long) {
        dao.insertSession(ChatAssistantSessionEntity(conversationId, title, now, now))
        dao.updateSession(conversationId, title, now)
    }
}
