package com.jev.relationship.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatAssistantDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: ChatAssistantSessionEntity)

    @Query("UPDATE chat_assistant_sessions SET title = :title, updatedAtMs = :updatedAtMs WHERE conversationId = :conversationId")
    suspend fun updateSession(conversationId: String, title: String, updatedAtMs: Long)

    @Query("SELECT * FROM chat_assistant_sessions WHERE conversationId = :conversationId LIMIT 1")
    suspend fun findSession(conversationId: String): ChatAssistantSessionEntity?

    @Insert
    suspend fun insertTurn(turn: ChatAssistantTurnEntity): Long

    @Query("SELECT * FROM chat_assistant_turns WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun findTurns(conversationId: String): List<ChatAssistantTurnEntity>
}
