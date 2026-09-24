package com.jev.relationship.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_assistant_turns",
    foreignKeys = [ForeignKey(
        entity = ChatAssistantSessionEntity::class,
        parentColumns = ["conversationId"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["conversationId", "id"])],
)
data class ChatAssistantTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAtMs: Long,
)
