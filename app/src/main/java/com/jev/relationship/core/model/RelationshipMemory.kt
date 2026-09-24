package com.jev.relationship.core.model

enum class MemoryKind(val label: String) {
    CommunicationStyle("沟通风格"),
    CommonTopic("常聊话题"),
    EmotionalPattern("情绪模式"),
    ImportantEvent("重要事件"),
    UserNote("我的备注"),
}

data class MemoryObservation(
    val id: Long,
    val contactId: String,
    val kind: MemoryKind,
    val text: String,
    val confidence: Double,
    val updatedAt: Long,
)

data class MemoryObservationDraft(
    val text: String,
    val kind: MemoryKind,
    val confidence: Double = 1.0,
) {
    fun toObservation(contactId: String, updatedAt: Long, id: Long = 0L): MemoryObservation? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        return MemoryObservation(
            id = id,
            contactId = contactId,
            kind = kind,
            text = normalized,
            confidence = confidence.coerceIn(0.0, 1.0),
            updatedAt = updatedAt,
        )
    }
}

