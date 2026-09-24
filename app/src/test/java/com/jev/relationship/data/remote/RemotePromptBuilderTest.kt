package com.jev.relationship.data.remote

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.MemoryKind
import com.jev.relationship.core.model.MemoryObservation
import com.jev.relationship.domain.AnalysisContext
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePromptBuilderTest {
    @Test
    fun `reply generation does not resend unlimited history`() {
        val prompt = RemotePromptBuilder.replyUserPrompt(
            AnalysisContext(Conversation("最早聊天" + "旧消息。".repeat(20_000) + "\n当前待分析消息：今晚几点见？")), "确认时间")
        assertTrue(prompt.length < 8_000)
        assertTrue(prompt.contains("当前待分析消息：今晚几点见？"))
        assertTrue(!prompt.contains("最早聊天"))
    }
    @Test
    fun replyPromptContainsConversationAndBoundedRelationshipMemory() {
        val prompt = RemotePromptBuilder.replyUserPrompt(
            AnalysisContext(
                conversation = Conversation("她：最近有点累", "contact-1"),
                observations = listOf(MemoryObservation(1L, "contact-1", MemoryKind.UserNote, "不喜欢长解释", 1.0, 1L)),
            ),
            suggestion = "先回应情绪",
        )

        assertTrue(prompt.contains("她：最近有点累"))
        assertTrue(prompt.contains("不喜欢长解释"))
    }
}
