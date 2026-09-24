package com.jev.relationship.domain.chatassistant

import com.jev.relationship.ipc.LocalChatRecord
import com.jev.relationship.ipc.ChatAssistantRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAssistantPromptBuilderTest {
    @Test
    fun `saving mode bounds long history and exchanges while retaining recent and relevant evidence`() {
        val transcript = (1L..1000L).map { id ->
            LocalChatRecord(id, if (id == 10L) "旧约定：蓝鲸项目周五交付" else "普通聊天$id：" + "最近的生活安排。".repeat(12), id * 1000, id % 2L == 0L)
        }
        val turns = (1..100).map { ChatAssistantConversationTurn(if (it % 2 == 0) "assistant" else "user", "旧问答$it" + "内容".repeat(100), it.toLong()) }
        val messages = ChatAssistantPromptBuilder.build("alice", transcript, turns, "蓝鲸项目约定哪天交付？", false)
        val text = messages.joinToString("\n") { it.content }
        assertTrue("Input must have a bounded size", text.length < 16_000)
        assertTrue(text.contains("普通聊天1000"))
        assertTrue(text.contains("蓝鲸项目周五交付"))
        assertTrue(text.contains("部分"))
        assertTrue(!text.contains("普通聊天500："))
        assertTrue(!text.contains("旧问答1内容"))
        assertEquals(100, turns.size)
        val full = ChatAssistantPromptBuilder.build("alice", transcript, turns, "蓝鲸项目约定哪天交付？", false, fullContext = true)
        val fullText = full.joinToString("\n") { it.content }
        assertTrue(fullText.contains("普通聊天500："))
        assertTrue(fullText.contains("旧问答1内容"))
        assertTrue(text.length * 5 < fullText.length)
        println("saving_input_chars=${text.length} full_input_chars=${fullText.length}")
    }

    @Test
    fun `saving mode caps an oversized message and exchange without dropping the latest content`() {
        val messages = ChatAssistantPromptBuilder.build("alice",
            listOf(LocalChatRecord(1, "start" + "超长".repeat(50_000) + "latest-end", 1L, false)),
            listOf(ChatAssistantConversationTurn("assistant", "旧".repeat(50_000) + "answer-end", 1L)), "解释末尾", false)
        val text = messages.joinToString("\n") { it.content }
        assertTrue(text.length < 14_000)
        assertTrue(text.contains("latest-end"))
        assertTrue(text.contains("answer-end"))
        assertTrue(text.contains("部分内容已省略"))
    }

    @Test
    fun `default report prompt remains unchanged and shared with the display renderer`() {
        assertEquals(
            "请基于最新完整聊天记录生成整体分析：只回复“收到”，再用不超过3句、100字说明你理解的核心意思，最后问“你有什么疑问？”。不要展开分析、列证据或给建议；群聊请区分成员，不确定时简短说明。",
            ChatAssistantPromptBuilder.REPORT_REQUEST,
        )
        assertEquals(ChatAssistantRequest.DEFAULT_ANALYSIS_PROMPT, ChatAssistantPromptBuilder.REPORT_REQUEST)
    }

    @Test
    fun `prompt contains complete chronological transcript then saved turns and current question`() {
        val messages = ChatAssistantPromptBuilder.build(
            conversationId = "alice",
            transcript = listOf(
                LocalChatRecord(11, "昨天一起吃饭吗", 100L, true),
                LocalChatRecord(12, "我今天有点累", 200L, false),
            ),
            turns = listOf(
                ChatAssistantConversationTurn("assistant", "她表达了疲惫。", 300L),
                ChatAssistantConversationTurn("user", "怎么问她比较自然？", 400L),
                ChatAssistantConversationTurn("assistant", "可以先关心她。", 500L),
            ),
            question = "她是在拒绝我吗？",
            refreshReport = false,
        )

        assertEquals(listOf("system", "system", "assistant", "user", "assistant", "user"), messages.map { it.role })
        assertTrue(messages[1].content.indexOf("我：昨天一起吃饭吗") < messages[1].content.indexOf("对方：我今天有点累"))
        assertEquals("她表达了疲惫。", messages[2].content)
        assertEquals("她是在拒绝我吗？", messages.last().content)
        assertTrue(messages[0].content.contains("区分原文证据与推测"))
    }

    @Test
    fun `report refresh asks for overall analysis without inventing a stored user turn`() {
        val messages = ChatAssistantPromptBuilder.build(
            conversationId = "alice",
            transcript = listOf(LocalChatRecord(1, "你撤回了一条消息", 100L, false)),
            turns = emptyList(),
            question = null,
            refreshReport = true,
        )

        assertEquals("system", messages[0].role)
        assertEquals("system", messages[1].role)
        assertEquals("user", messages.last().role)
        assertTrue(messages.last().content.contains("整体分析"))
        assertTrue(messages.last().content.contains("收到"))
        assertTrue(messages.last().content.contains("不超过3句"))
        assertTrue(messages.last().content.contains("你有什么疑问？"))
        assertTrue(messages[0].content.contains("简洁"))
        assertTrue(!messages[1].content.contains("你撤回了一条消息"))
    }

    @Test
    fun `group transcript identifies incoming messages as group members`() {
        val messages = ChatAssistantPromptBuilder.build(
            conversationId = "room@chatroom",
            transcript = listOf(LocalChatRecord(1, "周末在哪见？", 100L, false)),
            turns = emptyList(),
            question = null,
            refreshReport = true,
        )

        assertTrue(messages[1].content.contains("群成员：周末在哪见？"))
        assertTrue(messages[0].content.contains("群聊里的非本机发送者且可能是不同的人"))
    }
}
