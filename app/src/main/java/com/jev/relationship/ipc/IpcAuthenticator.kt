package com.jev.relationship.ipc

import com.jev.relationship.data.settings.XposedIntegrationSettings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object IpcAuthenticator {
    fun authenticate(
        hello: IpcHello,
        settings: XposedIntegrationSettings,
        callerPackages: Set<String>,
    ): HandshakeResult {
        if (!settings.enabled) return HandshakeResult(false, RejectReason.DISABLED)
        if (hello.protocolVersion != IpcProtocol.VERSION) {
            return HandshakeResult(false, RejectReason.PROTOCOL_MISMATCH)
        }
        if (hello.sourcePackage != IpcProtocol.WECHAT_PACKAGE) {
            return HandshakeResult(false, RejectReason.SOURCE_PACKAGE_NOT_ALLOWED)
        }
        if (IpcProtocol.WECHAT_PACKAGE !in callerPackages) {
            return HandshakeResult(false, RejectReason.CALLER_PACKAGE_NOT_ALLOWED)
        }
        val expectedToken = settings.pairingToken
            ?: return HandshakeResult(false, RejectReason.AUTHENTICATION_REQUIRED)
        val matches = MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            hello.pairingToken.toByteArray(StandardCharsets.UTF_8),
        )
        return if (matches) {
            HandshakeResult(accepted = true)
        } else {
            HandshakeResult(false, RejectReason.INVALID_TOKEN)
        }
    }
}
