package com.jev.relationship.ipc

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.data.settings.XposedIntegrationSettings

class IpcSession(
    private val settingsProvider: () -> XposedIntegrationSettings,
    private val coordinator: MessageCaptureCoordinator,
) {
    private var authenticated = false
    private var embeddedChatCardSupportedInternal = false

    val embeddedChatCardSupported: Boolean
        get() = embeddedChatCardSupportedInternal

    fun handshake(
        hello: IpcHello,
        callerPackages: Set<String>,
    ): HandshakeResult {
        val result = IpcAuthenticator.authenticate(
            hello = hello,
            settings = settingsProvider(),
            callerPackages = callerPackages,
        )
        authenticated = result.accepted
        if (authenticated) {
            embeddedChatCardSupportedInternal = IpcCapabilities.EMBEDDED_CHAT_CARD in hello.capabilities
            coordinator.activate()
        } else {
            embeddedChatCardSupportedInternal = false
            coordinator.reset()
        }
        return result
    }

    fun submit(message: CapturedMessage): SubmitResult = if (authenticated) {
        coordinator.submit(message)
    } else {
        SubmitResult(false, RejectReason.AUTHENTICATION_REQUIRED)
    }

    fun disconnect() {
        authenticated = false
        embeddedChatCardSupportedInternal = false
        coordinator.reset()
    }

    fun latestConversation(): Conversation? = coordinator.latestConversation()
}
