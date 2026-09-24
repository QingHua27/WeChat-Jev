package com.jev.relationship.ipc

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class IpcAnalysisResultBroadcasterTest {
    @Test fun `display preference arrives before cached results and survives reconnect`() {
        val received = mutableListOf<Message>()
        val broadcaster = IpcAnalysisResultBroadcaster { _, message -> received += message }
        broadcaster.setFastCacheDisplay(false)
        broadcaster.publish(result())
        val target = Messenger(Handler(Looper.getMainLooper()))
        val caps = setOf(IpcCapabilities.EMBEDDED_CHAT_CARD, IpcCapabilities.CACHE_PRELOAD)
        broadcaster.setClient(target, caps)
        assertEquals(IpcProtocol.MSG_CACHE_DISPLAY_MODE, received.first().what)
        assertFalse(received.first().data.getBoolean(IpcProtocol.KEY_FAST_CACHE_DISPLAY))
        assertEquals(IpcProtocol.MSG_ANALYSIS_RESULT, received[1].what)
        broadcaster.setFastCacheDisplay(true)
        assertTrue(received.last().data.getBoolean(IpcProtocol.KEY_FAST_CACHE_DISPLAY))
        broadcaster.clearClient()
        received.clear()
        broadcaster.setClient(target, caps)
        assertTrue(received.single().data.getBoolean(IpcProtocol.KEY_FAST_CACHE_DISPLAY))
    }
    @Test fun `batch capable client receives a group in one IPC message`() {
        val received = mutableListOf<Message>()
        val broadcaster = IpcAnalysisResultBroadcaster { _, message -> received += message }
        broadcaster.setClient(Messenger(Handler(Looper.getMainLooper())),
            setOf(IpcCapabilities.EMBEDDED_CHAT_CARD, "analysis_result_batch"))
        broadcaster.publishAll(listOf(result("one"), result("two")))
        assertEquals(1, received.size)
        assertEquals(IpcProtocol.MSG_ANALYSIS_BATCH, received.single().what)
        assertEquals(listOf(result("one"), result("two")), IpcCodec.decodeAnalysisResults(received.single().data))
    }

    @Test fun `batch sending is bounded and old clients still receive individual results`() {
        val received = mutableListOf<Message>()
        val broadcaster = IpcAnalysisResultBroadcaster { _, message -> received += message }
        val target = Messenger(Handler(Looper.getMainLooper()))
        val results = (1..25).map { result("m$it") }
        broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD, IpcCapabilities.ANALYSIS_RESULT_BATCH))
        broadcaster.publishAll(results)
        assertEquals(2, received.size)
        assertEquals(results, received.flatMap { IpcCodec.decodeAnalysisResults(it.data) })
        received.clear()
        broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD))
        broadcaster.publishAll(results)
        assertEquals(results, received.map { IpcCodec.decodeAnalysisResult(it.data) })
    }

    @Test fun `failed batch survives reconnect without losing its results`() {
        val received = mutableListOf<Message>()
        var failing = true
        val broadcaster = IpcAnalysisResultBroadcaster { _, message ->
            if (failing) throw android.os.RemoteException("disconnected")
            received += message
        }
        val target = Messenger(Handler(Looper.getMainLooper()))
        val capabilities = setOf(IpcCapabilities.EMBEDDED_CHAT_CARD, IpcCapabilities.ANALYSIS_RESULT_BATCH)
        broadcaster.setClient(target, capabilities)
        val expected = listOf(result("one"), result("two"))
        broadcaster.publishAll(expected)
        assertFalse(broadcaster.embeddedClientActive.value)
        failing = false
        broadcaster.setClient(target, capabilities)
        assertEquals(expected, IpcCodec.decodeAnalysisResults(received.single().data))
    }

    @Test
    fun `capable client receives one encoded analysis result`() {
        val received = mutableListOf<Message>()
        val target = Messenger(Handler(Looper.getMainLooper()) {
            received += Message.obtain(it)
            true
        })
        val broadcaster = IpcAnalysisResultBroadcaster()

        assertTrue(broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD)))
        broadcaster.publish(result())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, received.size)
        assertEquals(IpcProtocol.MSG_ANALYSIS_RESULT, received.single().what)
        assertEquals(result(), IpcCodec.decodeAnalysisResult(received.single().data))
    }

    @Test
    fun `latest result waits for a capable client that connects later`() {
        val received = mutableListOf<Message>()
        val target = Messenger(Handler(Looper.getMainLooper()) {
            received += Message.obtain(it)
            true
        })
        val broadcaster = IpcAnalysisResultBroadcaster()
        val expected = result()

        broadcaster.publish(expected)
        assertTrue(broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD)))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, received.size)
        assertEquals(expected, IpcCodec.decodeAnalysisResult(received.single().data))
    }

    @Test
    fun `all pending message results wait for a capable client`() {
        val received = mutableListOf<Message>()
        val target = Messenger(Handler(Looper.getMainLooper()) {
            received += Message.obtain(it)
            true
        })
        val broadcaster = IpcAnalysisResultBroadcaster()

        broadcaster.publish(result("message-1"))
        broadcaster.publish(result("message-2"))
        broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("message-1", "message-2"), received.map {
            IpcCodec.decodeAnalysisResult(it.data).messageId
        })
    }

    @Test
    fun `client without embedded capability cannot receive results`() {
        val broadcaster = IpcAnalysisResultBroadcaster()
        val target = Messenger(Handler(Looper.getMainLooper()))

        assertFalse(broadcaster.setClient(target, emptySet()))

        broadcaster.publish(result())

        assertFalse(broadcaster.embeddedClientActive.value)
    }

    @Test
    fun `clearing client prevents later sends`() {
        val broadcaster = IpcAnalysisResultBroadcaster()
        val target = Messenger(Handler(Looper.getMainLooper()))
        broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD))

        broadcaster.clearClient()
        broadcaster.publish(result())

        assertFalse(broadcaster.embeddedClientActive.value)
    }

    @Test
    fun `clearing analysis results keeps the capable client connected`() {
        val received = mutableListOf<Message>()
        val target = Messenger(Handler(Looper.getMainLooper()) {
            received += Message.obtain(it)
            true
        })
        val broadcaster = IpcAnalysisResultBroadcaster()
        broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD))

        broadcaster.clear()
        broadcaster.publish(result())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(broadcaster.embeddedClientActive.value)
        assertEquals(listOf(IpcProtocol.MSG_ANALYSIS_CLEAR, IpcProtocol.MSG_ANALYSIS_RESULT), received.map { it.what })
    }

    @Test
    fun `send failure clears active client`() {
        val broadcaster = IpcAnalysisResultBroadcaster(
            sendMessage = { _, _ -> throw android.os.RemoteException("client gone") },
        )
        val target = Messenger(Handler(Looper.getMainLooper()))
        broadcaster.setClient(target, setOf(IpcCapabilities.EMBEDDED_CHAT_CARD))

        broadcaster.publish(result())

        assertFalse(broadcaster.embeddedClientActive.value)
    }

    private fun result(messageId: String = "message-1") = IpcAnalysisResult(
        messageId = messageId,
        conversationHash = "conversation-hash",
        textHash = "text-hash",
        isOutgoing = false,
        emotion = "平静",
        intents = listOf(IpcIntentProbability("沟通", 0.8)),
        riskLevel = 3,
        suggestion = "保持清晰。",
    )
}
