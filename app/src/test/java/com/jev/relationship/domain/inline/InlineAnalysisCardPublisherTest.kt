package com.jev.relationship.domain.inline

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.domain.realtime.ConversationAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAnalysisCardPublisherTest {
    @Test
    fun `publishes stable hashes without retaining source text`() {
        val store = InlineCardStore()
        val publisher = InlineAnalysisCardPublisher(store)
        val output = AnalysisOutput(
            analysis = AnalysisResult(
                emotion = "平静",
                intents = listOf(IntentProbability("回应", 0.8)),
                riskLevel = 2,
                suggestion = "先回应",
            ),
            replies = emptyList(),
        )

        publisher.publish(
            messageId = "message-1",
            conversationId = "conversation-1",
            text = "仅用于哈希匹配的文本",
            isOutgoing = false,
            output = output,
        )

        val card = requireNotNull(store.state.value)
        assertEquals("message-1", card.anchor.messageId)
        assertNotEquals("conversation-1", card.anchor.conversationHash)
        assertNotEquals("仅用于哈希匹配的文本", card.anchor.textHash)
        assertEquals(output, card.output)
    }

    @Test
    fun `matches identifies the current conversation and message text`() {
        val store = InlineCardStore()
        val publisher = InlineAnalysisCardPublisher(store)
        val output = AnalysisOutput(
            analysis = AnalysisResult(
                emotion = "平静",
                intents = listOf(IntentProbability("回应", 0.8)),
                riskLevel = 2,
                suggestion = "先回应",
            ),
            replies = emptyList(),
        )

        publisher.publish(
            messageId = "message-1",
            conversationId = "chat-a",
            text = "hello",
            isOutgoing = false,
            output = output,
        )

        assertTrue(publisher.matches("chat-a", "hello"))
        assertFalse(publisher.matches("chat-b", "hello"))
        assertFalse(publisher.matches("chat-a", "different"))
    }
}
