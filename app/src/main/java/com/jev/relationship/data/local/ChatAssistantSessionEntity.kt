package com.jev.relationship.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_assistant_sessions")
data class ChatAssistantSessionEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
