package com.jev.relationship.core.model

@JvmInline
value class ConversationId(val value: Long)

data class Conversation(
    val text: String,
    val contactId: String? = null,
    /** Local history has already been bounded by message count, preserving the complete target. */
    val hasBoundedMessageContext: Boolean = false,
)
