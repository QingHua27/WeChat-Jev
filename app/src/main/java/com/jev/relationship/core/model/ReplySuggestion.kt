package com.jev.relationship.core.model

enum class ReplyTone(val label: String) {
    Gentle("温柔"),
    Humorous("幽默"),
    Serious("认真"),
}

data class ReplySuggestion(
    val tone: ReplyTone,
    val text: String,
)
