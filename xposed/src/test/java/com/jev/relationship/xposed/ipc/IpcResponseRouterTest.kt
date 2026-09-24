package com.jev.relationship.xposed.ipc

import android.os.Message
import com.jev.relationship.ipc.HandshakeResult
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcCodec
import com.jev.relationship.ipc.IpcIntentProbability
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.ChatAssistantResult
import com.jev.relationship.ipc.ChatAssistantTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcResponseRouterTest {
    @Test fun `cache display setting routes explicit values and ignores missing field`() {
        val modes = mutableListOf<Boolean>()
        val router = IpcResponseRouter({}, {}, onFastCacheDisplay = modes::add)
        router.handle(Message.obtain(null, IpcProtocol.MSG_CACHE_DISPLAY_MODE))
        listOf(true, false).forEach { enabled ->
            router.handle(Message.obtain(null, IpcProtocol.MSG_CACHE_DISPLAY_MODE).apply {
                data = android.os.Bundle().apply { putBoolean(IpcProtocol.KEY_FAST_CACHE_DISPLAY, enabled) }
            })
        }
        assertEquals(listOf(true, false), modes)
    }
    @Test fun `analysis batches route atomically and reject malformed groups`() {
        val batches = mutableListOf<List<IpcAnalysisResult>>()
        val router = IpcResponseRouter({}, { error("batch must not be split into individual callbacks") },
            onAnalysisResults = batches::add)
        val expected = listOf(result(), result().copy(messageId = "second"))
        assertTrue(router.handle(Message.obtain(null, IpcProtocol.MSG_ANALYSIS_BATCH).apply {
            data = IpcCodec.encodeAnalysisResults(expected)
        }))
        assertEquals(listOf(expected), batches)
        router.handle(Message.obtain(null, IpcProtocol.MSG_ANALYSIS_BATCH).apply {
            data = android.os.Bundle().apply {
                putParcelableArrayList(IpcProtocol.KEY_ANALYSIS_RESULTS,
                    arrayListOf(IpcCodec.encodeAnalysisResult(result()), android.os.Bundle()))
            }
        })
        assertEquals("invalid batch must not partially render", 1, batches.size)
    }

    @Test
    fun `partial results keep request pending until final and reject another conversation`() {
        val results = mutableListOf<ChatAssistantResult>()
        val router = IpcResponseRouter({}, {})
        router.registerAssistantRequest("r", "alice", results::add)
        fun deliver(chat: String, text: String, partial: Boolean) {
            router.handle(Message.obtain(null, if (partial) IpcProtocol.MSG_CHAT_ASSISTANT_PROGRESS else IpcProtocol.MSG_CHAT_ASSISTANT_RESULT).apply {
                data = ChatAssistantResult("r", chat,
                    listOf(ChatAssistantTurn("assistant", text, 1L)), isPartial = partial).toBundle()
            })
        }
        deliver("bob", "wrong chat", true)
        assertTrue(results.isEmpty())
        deliver("alice", "first", true)
        deliver("alice", "first second", true)
        deliver("alice", "final", false)
        deliver("alice", "late", true)
        assertEquals(listOf("first", "first second", "final"), results.map { it.turns.single().content })
        assertEquals(listOf(true, true, false), results.map { it.isPartial })
    }

    @Test
    fun `partial result does not prevent timeout or cancellation`() {
        val results = mutableListOf<ChatAssistantResult>()
        val router = IpcResponseRouter({}, {})
        router.registerAssistantRequest("r", "alice", results::add)
        router.handle(Message.obtain(null, IpcProtocol.MSG_CHAT_ASSISTANT_PROGRESS).apply {
            data = ChatAssistantResult("r", "alice", listOf(ChatAssistantTurn("assistant", "draft", 1L)), isPartial = true).toBundle()
        })
        assertTrue(router.failAssistantRequest("r", "timeout"))
        assertEquals("timeout", results.last().error)
        assertEquals(false, router.cancelAssistantRequest("r"))
    }

    @Test
    fun `clear command removes analysis without disconnecting handshake`() {
        var cleared = false
        val router = IpcResponseRouter({}, {}, onAnalysisCleared = { cleared = true })
        assertTrue(router.handle(Message.obtain(null, IpcProtocol.MSG_ANALYSIS_CLEAR)))
        assertTrue(cleared)
    }
    @Test
    fun `routes a valid analysis result to the embedded host`() {
        val results = mutableListOf<IpcAnalysisResult>()
        val router = IpcResponseRouter(
            onHandshake = {},
            onAnalysisResult = results::add,
        )

        val handled = router.handle(
            Message.obtain(null, IpcProtocol.MSG_ANALYSIS_RESULT).apply {
                data = IpcCodec.encodeAnalysisResult(result())
            },
        )

        assertTrue(handled)
        assertEquals(listOf(result()), results)
    }

    @Test
    fun `ignores malformed analysis result`() {
        val results = mutableListOf<IpcAnalysisResult>()
        val router = IpcResponseRouter({}, results::add)

        val handled = router.handle(Message.obtain(null, IpcProtocol.MSG_ANALYSIS_RESULT))

        assertTrue(handled)
        assertFalse(results.isNotEmpty())
    }

    @Test
    fun `routes handshake result`() {
        var accepted: HandshakeResult? = null
        val router = IpcResponseRouter(onHandshake = { accepted = it }, onAnalysisResult = {})

        val handled = router.handle(
            Message.obtain(null, IpcProtocol.MSG_HANDSHAKE_RESULT).apply {
                data = IpcCodec.encodeHandshakeResult(HandshakeResult(accepted = true))
            },
        )

        assertTrue(handled)
        assertEquals(true, accepted?.accepted)
    }

    @Test
    fun `routes only assistant results with matching request ids`() {
        var received: ChatAssistantResult? = null
        val router = IpcResponseRouter({}, {})
        router.registerAssistantRequest("request-1", "alice") { received = it }

        assertTrue(router.handle(Message.obtain(null, IpcProtocol.MSG_CHAT_ASSISTANT_RESULT).apply {
            data = ChatAssistantResult(
                "request-1",
                "alice",
                listOf(ChatAssistantTurn("assistant", "report", 1L)),
            ).toBundle()
        }))

        assertEquals("report", received?.turns?.single()?.content)
        assertTrue(router.handle(Message.obtain(null, IpcProtocol.MSG_CHAT_ASSISTANT_RESULT).apply {
            data = ChatAssistantResult("unknown", "alice", emptyList()).toBundle()
        }))
        assertEquals("report", received?.turns?.single()?.content)
    }

    @Test
    fun `disconnect fails pending assistant request with its conversation identity`() {
        var received: ChatAssistantResult? = null
        val router = IpcResponseRouter({}, {})
        router.registerAssistantRequest("request-1", "alice") { received = it }

        router.failAssistantRequests("Jev 连接已断开")

        assertEquals("alice", received?.conversationId)
        assertEquals("Jev 连接已断开", received?.error)
    }

    @Test
    fun `cancel removes pending assistant request without delivering later result`() {
        var callbackCount = 0
        val router = IpcResponseRouter({}, {})
        router.registerAssistantRequest("request-1", "alice") { callbackCount++ }

        assertTrue(router.cancelAssistantRequest("request-1"))
        assertFalse(router.cancelAssistantRequest("request-1"))
        router.handle(Message.obtain(null, IpcProtocol.MSG_CHAT_ASSISTANT_RESULT).apply {
            data = ChatAssistantResult("request-1", "alice", emptyList()).toBundle()
        })

        assertEquals(0, callbackCount)
    }

    private fun result() = IpcAnalysisResult(
        messageId = "message-1",
        conversationHash = "conversation-hash",
        textHash = "text-hash",
        isOutgoing = false,
        emotion = "平静",
        intents = listOf(IpcIntentProbability("沟通", 0.8)),
        riskLevel = 3,
        suggestion = "保持清晰。",
    )
}
