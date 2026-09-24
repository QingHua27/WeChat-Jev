package com.jev.relationship.xposed.ipc

import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.HandshakeResult
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.IpcCapabilities
import com.jev.relationship.ipc.MessageSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcClientStateTest {
    @Test
    fun doesNotProduceMessagesBeforeHandshake() {
        val state = IpcClientState(tokenProvider = { "token" })
        state.enqueue(message("queued"))

        assertEquals(null, state.nextAuthenticatedMessage())
    }

    @Test
    fun authenticatedHandshakeReleasesQueuedMessages() {
        val state = IpcClientState(tokenProvider = { "token" })
        state.enqueue(message("queued"))
        state.onHandshakeResult(HandshakeResult(accepted = true))

        assertEquals("queued", state.nextAuthenticatedMessage()?.text)
        assertEquals(null, state.nextAuthenticatedMessage())
    }

    @Test
    fun missingTokenAllowsBootstrapButPreventsHandshakeAndSubmission() {
        var token: String? = null
        val state = IpcClientState(tokenProvider = { token })
        assertTrue(state.canConnect())
        assertEquals(null, state.hello("1"))
        state.enqueue(message("queued"))
        assertEquals(null, state.nextAuthenticatedMessage())
        token = "automatically-provisioned-token"
        assertEquals(token, state.hello("1")?.pairingToken)
        assertEquals(null, state.nextAuthenticatedMessage())
    }

    @Test
    fun helloDeclaresEmbeddedChatCardCapability() {
        val state = IpcClientState(tokenProvider = { "token" })

        val hello = state.hello("0.2.0")

        assertTrue(hello?.capabilities?.contains(IpcCapabilities.EMBEDDED_CHAT_CARD) == true)
    }

    @Test
    fun queueIsBoundedByProtocolLimit() {
        val state = IpcClientState(tokenProvider = { "token" }, maxQueueSize = 2)
        state.enqueue(message("first"))
        state.enqueue(message("second"))
        assertTrue(state.enqueue(message("third")))
        state.onHandshakeResult(HandshakeResult(accepted = true))

        assertEquals(listOf("second", "third"), listOfNotNull(
            state.nextAuthenticatedMessage()?.text,
            state.nextAuthenticatedMessage()?.text,
        ))
    }

    private fun message(text: String) = CapturedMessage(
        conversationId = "chat-1",
        sender = MessageSender.CONTACT,
        text = text,
        timestampMs = 1_000L,
        sourcePackage = IpcProtocol.WECHAT_PACKAGE,
        sourceClass = null,
        isOutgoing = false,
        messageId = null,
    )
}
