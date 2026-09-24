package com.jev.relationship.ipc

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.domain.source.ConversationSourceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MessageCaptureCoordinator(
    private val maxMessagesPerConversation: Int = IpcProtocol.MAX_BATCH_SIZE,
) {
    private val _state = MutableStateFlow<ConversationSourceState>(ConversationSourceState.Disabled)
    private val _events = MutableSharedFlow<CapturedMessage>(
        extraBufferCapacity = IpcProtocol.MAX_BATCH_SIZE,
    )
    private val messagesByConversation = linkedMapOf<String, MutableList<CapturedMessage>>()
    private var latestConversationId: String? = null

    val state: StateFlow<ConversationSourceState> = _state.asStateFlow()
    val events: SharedFlow<CapturedMessage> = _events.asSharedFlow()

    fun activate() {
        _state.value = ConversationSourceState.Active
    }

    fun submit(message: CapturedMessage): SubmitResult {
        if (_state.value !is ConversationSourceState.Active) {
            return SubmitResult(false, RejectReason.AUTHENTICATION_REQUIRED)
        }
        val validation = CapturedMessageValidator.validate(message)
        if (!validation.accepted) {
            return SubmitResult(false, validation.reason)
        }
        val messages = messagesByConversation.getOrPut(message.conversationId) { mutableListOf() }
        messages += message
        messages.sortBy { it.timestampMs }
        while (messages.size > maxMessagesPerConversation) messages.removeAt(0)
        latestConversationId = message.conversationId
        _events.tryEmit(message)
        return SubmitResult(accepted = true)
    }

    fun reset() {
        messagesByConversation.clear()
        latestConversationId = null
        _state.value = ConversationSourceState.Disabled
    }

    fun latestConversation(): Conversation? {
        val id = latestConversationId ?: return null
        return latestConversation(id)
    }

    fun latestConversation(conversationId: String): Conversation? {
        val messages = messagesByConversation[conversationId].orEmpty()
        if (messages.isEmpty()) return null
        return Conversation(text = messages.joinToString("\n") { it.text })
    }

    fun conversationThrough(target: CapturedMessage): Conversation? {
        val messages = messagesByConversation[target.conversationId].orEmpty()
        val index = messages.indexOf(target)
        if (index < 0) return null
        return Conversation(messages.take(index + 1).joinToString("\n") {
            (if (it.isOutgoing || it.sender == MessageSender.SELF) "我：" else "对方：") + it.text
        } + "\n\n当前待分析消息：" + target.text)
    }

}
