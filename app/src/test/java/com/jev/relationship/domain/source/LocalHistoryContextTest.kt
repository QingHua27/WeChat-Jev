package com.jev.relationship.domain.source

import com.jev.relationship.ipc.LocalChatRecord
import org.junit.Assert.*
import org.junit.Test

class LocalHistoryContextTest {
    @Test
    fun `each target gets at most sixty preceding records without future or older context`() {
        val history = (1L..100L).map { record(it, "消息[$it]", it % 2L == 0L) }
        val text = LocalHistoryContext.forRecord(history, 69).text
        assertTrue(text.contains("消息[11]"))
        assertTrue(text.endsWith("当前待分析消息：消息[70]"))
        assertFalse(text.contains("消息[10]"))
        assertFalse(text.contains("消息[71]"))
    }

    @Test
    fun `offscreen history and own replies precede target without later messages`() {
        val history = listOf(record(1, "周末想出去吃饭"), record(2, "我来安排", true), record(3, "你还记得吗"), record(4, "后来的消息"))
        val visible = listOf(VisibleChatMessage("你还记得吗", false, 0))
        val contexts = LocalHistoryContext.forVisible(history, visible)
        val text = contexts.single().text
        assertTrue(text.contains("对方：周末想出去吃饭"))
        assertTrue(text.contains("我：我来安排"))
        assertTrue(text.endsWith("当前待分析消息：你还记得吗"))
        assertFalse(text.contains("后来的消息"))
    }

    @Test(expected = IllegalStateException::class)
    fun `ambiguous repeated text is not guessed`() {
        LocalHistoryContext.forVisible(listOf(record(1, "好的"), record(2, "好的")), listOf(VisibleChatMessage("好的", false, 0)))
    }

    @Test
    fun `stable local id disambiguates repeated group messages`() {
        val history = listOf(record(41, "wxid_member_a: 收到"), record(42, "wxid_member_b: 收到"), record(43, "wxid_member_c: 后续消息"))
        val visible = listOf(VisibleChatMessage("收到", false, 0, localMessageId = 42))

        assertEquals(listOf(42L), LocalHistoryContext.matchVisibleRecords(history, visible, isGroupChat = true).map { it.id })
        assertTrue(LocalHistoryContext.forVisible(history, visible, isGroupChat = true).single().text.contains("当前待分析消息：wxid_member_b"))
        assertFalse(LocalHistoryContext.forVisible(history, visible, isGroupChat = true).single().text.contains("后续消息"))
    }

    @Test
    fun `surrounding messages disambiguate a repeat`() {
        val history = listOf(record(1, "好的"), record(2, "具体安排", true), record(3, "好的"))
        val visible = listOf(VisibleChatMessage("具体安排", true, 0), VisibleChatMessage("好的", false, 0))
        assertTrue(LocalHistoryContext.forVisible(history, visible)[1].text.contains("我：具体安排"))
    }

    @Test
    fun `visible records expose stable local ids for card anchoring`() {
        val history = listOf(record(11, "屏幕外", true), record(12, "当前消息", false))
        val visible = listOf(VisibleChatMessage("当前消息", false, 0))

        assertEquals(listOf(12L), LocalHistoryContext.matchVisibleRecords(history, visible).map { it.id })
    }

    private fun record(id: Long, text: String, outgoing: Boolean = false) = LocalChatRecord(id, text, id * 1000, outgoing)
}
