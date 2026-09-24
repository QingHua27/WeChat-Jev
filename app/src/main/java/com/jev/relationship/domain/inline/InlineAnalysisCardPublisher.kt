package com.jev.relationship.domain.inline

import com.jev.relationship.domain.AnalysisOutput

class InlineAnalysisCardPublisher(
    private val store: InlineCardStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun publish(
        messageId: String,
        conversationId: String,
        text: String,
        isOutgoing: Boolean,
        output: AnalysisOutput,
        historyId: Long? = null,
    ) {
        store.show(
            InlineAnalysisCard(
                anchor = InlineMessageAnchor(
                    messageId = messageId,
                    conversationHash = InlineTextHasher.hash(conversationId),
                    textHash = InlineTextHasher.hash(text),
                    isOutgoing = isOutgoing,
                ),
                output = output,
                updatedAt = now(),
                historyId = historyId,
            ),
        )
    }

    fun clear(messageId: String? = null) {
        store.clear(messageId)
    }

    fun matches(conversationId: String, text: String): Boolean {
        val anchor = store.state.value?.anchor ?: return false
        return anchor.conversationHash == InlineTextHasher.hash(conversationId) &&
            anchor.textHash == InlineTextHasher.hash(text)
    }

}
