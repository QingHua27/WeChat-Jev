package com.jev.relationship.domain.inline

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.domain.AnalysisOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineCardStoreTest {
    @Test
    fun `show replaces previous card and clear removes it`() {
        val store = InlineCardStore()
        val first = card("message-1")
        val second = card("message-2")

        store.show(first)
        assertEquals(first, store.state.value)

        store.show(second)
        assertEquals(second, store.state.value)

        store.clear(messageId = "message-1")
        assertEquals(second, store.state.value)

        store.clear(messageId = "message-2")
        assertNull(store.state.value)
    }

    private fun card(messageId: String) = InlineAnalysisCard(
        anchor = InlineMessageAnchor(
            messageId = messageId,
            conversationHash = "conversation-hash",
            textHash = "text-hash",
            isOutgoing = false,
        ),
        output = AnalysisOutput(
            analysis = AnalysisResult(
                emotion = "平静",
                intents = emptyList(),
                riskLevel = 1,
                suggestion = "保持简洁",
            ),
            replies = emptyList(),
        ),
        updatedAt = 1L,
    )
}
