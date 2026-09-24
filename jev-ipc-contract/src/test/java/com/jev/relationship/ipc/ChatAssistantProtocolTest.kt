package com.jev.relationship.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.os.Bundle

@RunWith(RobolectricTestRunner::class)
class ChatAssistantProtocolTest {
    @Test
    fun `partial response preserves streaming state through IPC`() {
        val result = ChatAssistantResult("r", "alice",
            listOf(ChatAssistantTurn("assistant", "partial answer", 1L)), isPartial = true)
        assertEquals(result, ChatAssistantResult.fromBundle(result.toBundle()))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { result.copy(error = "failure").toBundle() }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { result.copy(turns = emptyList()).toBundle() }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            result.copy(turns = listOf(ChatAssistantTurn("user", "invalid", 1L))).toBundle()
        }
    }

    @Test
    fun `malformed request returns correlated failure when identifiers are valid`() {
        val failure = ChatAssistantRequest.validationFailure(Bundle().apply {
            putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, "request-1")
            putString(IpcProtocol.KEY_CONVERSATION_ID, "alice")
        })

        assertEquals("request-1", failure?.requestId)
        assertEquals("alice", failure?.conversationId)
        assertEquals("分析请求格式无效，请重试。", failure?.error)
        assertNull(ChatAssistantRequest.validationFailure(Bundle()))
    }
    @Test
    fun `request preserves identity and optional question through bundle`() {
        val requests = listOf(
            ChatAssistantRequest("req-1", "alice", "Alice"),
            ChatAssistantRequest("req-2", "room@chatroom", "Friends", "What did they mean?"),
            ChatAssistantRequest("req-3", "alice", "Alice", resumeSession = true),
            ChatAssistantRequest("req-4", "alice", "Alice", fullContext = true),
        )

        requests.forEach { assertEquals(it, ChatAssistantRequest.fromBundle(it.toBundle())) }
    }

    @Test
    fun `result preserves ordered turns and error through bundle`() {
        val result = ChatAssistantResult(
            requestId = "req-1",
            conversationId = "alice",
            turns = listOf(
                ChatAssistantTurn("assistant", "整体上，对方在表达不满。", 10L),
                ChatAssistantTurn("user", "她为什么这样说？", 20L),
                ChatAssistantTurn("assistant", "从这段话看……", 30L),
            ),
        )

        assertEquals(result, ChatAssistantResult.fromBundle(result.toBundle()))
    }

    @Test
    fun `result carries a readable error without partial turns`() {
        val result = ChatAssistantResult("req-1", "alice", emptyList(), "读取聊天记录失败")

        assertEquals(result, ChatAssistantResult.fromBundle(result.toBundle()))
        assertNull(ChatAssistantResult.fromBundle(result.toBundle()).turns.firstOrNull())
    }

    @Test
    fun `long session response keeps recent turns without truncating their contents`() {
        val fullTurns = List(205) { ChatAssistantTurn("assistant", "complete response $it", it.toLong()) }
        val result = ChatAssistantResult.forDisplay("req-1", "alice", fullTurns)

        assertEquals(ChatAssistantResult.MAX_TURNS, result.turns.size)
        assertEquals(fullTurns.takeLast(ChatAssistantResult.MAX_TURNS), result.turns)
        assertEquals(true, result.olderTurnsOmitted)
        assertEquals(result, ChatAssistantResult.fromBundle(result.toBundle()))
    }

    @Test
    fun `request rejects blank identity and oversized question`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ChatAssistantRequest("invalid", "alice", "Alice", "question", resumeSession = true).toBundle()
        }
        val blankConversation = Bundle().apply {
            putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, "req")
            putString(IpcProtocol.KEY_CONVERSATION_ID, "")
            putString(IpcProtocol.KEY_CONVERSATION_TITLE, "Alice")
        }
        val oversizedQuestion = Bundle().apply {
            putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, "req")
            putString(IpcProtocol.KEY_CONVERSATION_ID, "alice")
            putString(IpcProtocol.KEY_CONVERSATION_TITLE, "Alice")
            putString(IpcProtocol.KEY_ASSISTANT_QUESTION, "x".repeat(ChatAssistantRequest.MAX_QUESTION_LENGTH + 1))
        }

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ChatAssistantRequest.fromBundle(blankConversation)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ChatAssistantRequest.fromBundle(oversizedQuestion)
        }
    }

    @Test
    fun `result rejects unsupported role and oversized payload`() {
        val wrongRole = resultBundle(listOf(Bundle().apply {
            putString("role", "system")
            putString("content", "hidden instruction")
            putLong("created_at", 1L)
        }))
        val tooMany = resultBundle(List(ChatAssistantResult.MAX_TURNS + 1) {
            Bundle().apply {
                putString("role", "assistant")
                putString("content", "ok")
                putLong("created_at", it.toLong())
            }
        })

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ChatAssistantResult.fromBundle(wrongRole)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ChatAssistantResult.fromBundle(tooMany)
        }
    }

    private fun resultBundle(turns: List<Bundle>) = Bundle().apply {
        putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, "req")
        putString(IpcProtocol.KEY_CONVERSATION_ID, "alice")
        putParcelableArrayList(IpcProtocol.KEY_ASSISTANT_TURNS, ArrayList(turns))
    }
}
