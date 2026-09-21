package com.jev.relationship.xposed

import com.jev.relationship.xposed.hook.WechatVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatHookActivationTest {
    private val supportedVersion = WechatVersion("8.0.72", 3085L)

    @Test
    fun `requires supported main process version and non blank token`() {
        assertTrue(
            WechatHookActivation.shouldInstall(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion,
                pairingToken = "token",
            ),
        )
    }

    @Test
    fun `rejects missing or blank token`() {
        assertFalse(WechatHookActivation.shouldInstall("com.tencent.mm", "com.tencent.mm", supportedVersion, null))
        assertFalse(WechatHookActivation.shouldInstall("com.tencent.mm", "com.tencent.mm", supportedVersion, "  "))
    }

    @Test
    fun `rejects child process and unsupported version`() {
        assertFalse(WechatHookActivation.shouldInstall("com.tencent.mm", "com.tencent.mm:push", supportedVersion, "token"))
        assertFalse(
            WechatHookActivation.shouldInstall(
                "com.tencent.mm",
                "com.tencent.mm",
                supportedVersion.copy(versionName = "8.0.71"),
                "token",
            ),
        )
    }
}
