package com.jev.relationship.xposed.ipc

import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.HandshakeResult
import com.jev.relationship.ipc.IpcHello
import com.jev.relationship.ipc.IpcCapabilities
import com.jev.relationship.ipc.IpcProtocol

class IpcClientState(
    private val tokenProvider: () -> String?,
    private val maxQueueSize: Int = IpcProtocol.MAX_BATCH_SIZE,
) {
    private val queue = ArrayDeque<CapturedMessage>()
    private var authenticated = false

    @Synchronized
    fun canConnect(): Boolean = true // Binding bootstraps the merged app; hello still requires a token.

    @Synchronized
    fun hello(moduleVersion: String): IpcHello? {
        val token = tokenProvider()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return IpcHello(
            protocolVersion = IpcProtocol.VERSION,
            pairingToken = token,
            sourcePackage = IpcProtocol.WECHAT_PACKAGE,
            moduleVersion = moduleVersion,
            capabilities = setOf(IpcCapabilities.EMBEDDED_CHAT_CARD, IpcCapabilities.LOCAL_HISTORY),
        )
    }

    @Synchronized
    fun enqueue(message: CapturedMessage): Boolean {
        if (message.sourcePackage != IpcProtocol.WECHAT_PACKAGE || message.text.isBlank()) return false
        if (queue.size >= maxQueueSize) queue.removeFirst()
        queue.addLast(message)
        return true
    }

    @Synchronized
    fun onHandshakeResult(result: HandshakeResult) {
        authenticated = result.accepted
    }

    @Synchronized
    fun nextAuthenticatedMessage(): CapturedMessage? =
        if (authenticated) queue.removeFirstOrNull() else null

    @Synchronized
    fun onDisconnected() {
        authenticated = false
    }
}
