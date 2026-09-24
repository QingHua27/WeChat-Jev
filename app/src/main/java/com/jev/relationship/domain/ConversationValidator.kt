package com.jev.relationship.domain

import com.jev.relationship.core.model.Conversation

sealed interface ConversationValidation {
    data class Valid(val conversation: Conversation) : ConversationValidation {
        constructor(text: String) : this(Conversation(text))
    }

    data class Invalid(val error: Error) : ConversationValidation

    enum class Error {
        Empty,
    }
}

class ConversationValidator {
    fun validate(rawText: String): ConversationValidation {
        val normalized = rawText.trim()
        return if (normalized.isEmpty()) {
            ConversationValidation.Invalid(ConversationValidation.Error.Empty)
        } else {
            ConversationValidation.Valid(normalized)
        }
    }
}

fun normalizeConfidence(value: Double): Double = value.coerceIn(0.0, 1.0)

fun normalizeRisk(value: Int): Int = value.coerceIn(0, 10)

