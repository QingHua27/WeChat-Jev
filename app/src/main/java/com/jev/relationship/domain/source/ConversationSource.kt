package com.jev.relationship.domain.source

import com.jev.relationship.core.model.Conversation
import kotlinx.coroutines.flow.StateFlow

sealed interface ConversationSourceState {
    data object Disabled : ConversationSourceState
    data object PermissionRequired : ConversationSourceState
    data object Active : ConversationSourceState
    data class Error(val message: String) : ConversationSourceState
}

interface ConversationSource {
    val state: StateFlow<ConversationSourceState>
    fun start()
    fun stop()
    fun latestConversation(): Conversation?
}

