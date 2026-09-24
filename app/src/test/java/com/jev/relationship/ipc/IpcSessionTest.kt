package com.jev.relationship.ipc

import com.jev.relationship.data.settings.XposedIntegrationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcSessionTest {
    @Test
    fun submitRequiresSuccessfulHandshake() {
        val session = session()

        val result = session.submit(message("before handshake"))

        assertEquals(RejectReason.AUTHENTICATION_REQUIRED, result.reason)
    }

    @Test
    fun successfulHandshakeActivatesCoordinatorAndAcceptsMessage() {
        val session = session()

        val handshake = session.handshake(
            hello = hello(),
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        )
        val submit = session.submit(message("after handshake"))

        assertTrue(handshake.accepted)
        assertTrue(submit.accepted)
        assertEquals("after handshake", session.latestConversation()?.text)
    }

    @Test
    fun failedHandshakeDoesNotActivateCoordinator() {
        val session = session()

        val result = session.handshake(
            hello = hello(token = "wrong"),
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        )

        assertEquals(RejectReason.INVALID_TOKEN, result.reason)
        assertEquals(RejectReason.AUTHENTICATION_REQUIRED, session.submit(message("not accepted")).reason)
    }

    @Test
    fun disconnectInvalidatesAuthenticatedSession() {
        val session = session()
        session.handshake(hello(), setOf(IpcProtocol.WECHAT_PACKAGE))

        session.disconnect()

        assertEquals(RejectReason.AUTHENTICATION_REQUIRED, session.submit(message("after disconnect")).reason)
    }

    @Test
    fun tracksEmbeddedCapabilityOnlyForAuthenticatedHandshake() {
        val session = session()

        session.handshake(
            hello(capabilities = setOf(IpcCapabilities.EMBEDDED_CHAT_CARD)),
            setOf(IpcProtocol.WECHAT_PACKAGE),
        )

        assertTrue(session.embeddedChatCardSupported)

        session.disconnect()

        assertEquals(false, session.embeddedChatCardSupported)
    }

    private fun session() = IpcSession(
        settingsProvider = {
            XposedIntegrationSettings(enabled = true, pairingToken = "pairing-token")
        },
        coordinator = MessageCaptureCoordinator(),
    )

    private fun hello(
        token: String = "pairing-token",
        capabilities: Set<String> = emptySet(),
    ) = IpcHello(
        protocolVersion = IpcProtocol.VERSION,
        pairingToken = token,
        sourcePackage = IpcProtocol.WECHAT_PACKAGE,
        moduleVersion = "0.1.0",
        capabilities = capabilities,
    )

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
