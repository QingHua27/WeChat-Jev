package com.jev.relationship.ipc

import com.jev.relationship.data.settings.XposedIntegrationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcAuthenticatorTest {
    private val enabledSettings = XposedIntegrationSettings(
        enabled = true,
        pairingToken = "pairing-token",
    )

    @Test
    fun acceptsEnabledWeChatCallerWithMatchingToken() {
        val result = IpcAuthenticator.authenticate(
            hello = hello(token = "pairing-token"),
            settings = enabledSettings,
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        )

        assertTrue(result.accepted)
    }

    @Test
    fun rejectsWhenIntegrationIsDisabled() {
        val result = IpcAuthenticator.authenticate(
            hello = hello(token = "pairing-token"),
            settings = enabledSettings.copy(enabled = false),
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        )

        assertEquals(RejectReason.DISABLED, result.reason)
    }

    @Test
    fun rejectsWrongToken() {
        val result = IpcAuthenticator.authenticate(
            hello = hello(token = "wrong"),
            settings = enabledSettings,
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        )

        assertEquals(RejectReason.INVALID_TOKEN, result.reason)
    }

    @Test
    fun rejectsCallerWithoutWeChatPackage() {
        val result = IpcAuthenticator.authenticate(
            hello = hello(token = "pairing-token"),
            settings = enabledSettings,
            callerPackages = setOf("com.example.other"),
        )

        assertEquals(RejectReason.CALLER_PACKAGE_NOT_ALLOWED, result.reason)
    }

    @Test
    fun rejectsProtocolMismatch() {
        val result = IpcAuthenticator.authenticate(
            hello = hello(token = "pairing-token").copy(protocolVersion = IpcProtocol.VERSION + 1),
            settings = enabledSettings,
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        )

        assertEquals(RejectReason.PROTOCOL_MISMATCH, result.reason)
    }

    private fun hello(token: String) = IpcHello(
        protocolVersion = IpcProtocol.VERSION,
        pairingToken = token,
        sourcePackage = IpcProtocol.WECHAT_PACKAGE,
        moduleVersion = "0.1.0",
    )
}
