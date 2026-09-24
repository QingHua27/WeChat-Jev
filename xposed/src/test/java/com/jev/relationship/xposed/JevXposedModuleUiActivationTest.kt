package com.jev.relationship.xposed

import com.jev.relationship.xposed.hook.WechatVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevXposedModuleUiActivationTest {
    private val supportedVersion = WechatVersion("8.0.72", 3085L)

    @Test
    fun `ui hook requires the paired supported WeChat main process`() {
        assertTrue(
            WechatUiHookActivation.shouldInstall(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion,
                pairingToken = "token",
            ),
        )
    }

    @Test
    fun `ui hook rejects unsupported version or process`() {
        assertFalse(
            WechatUiHookActivation.shouldInstall(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm:push",
                version = supportedVersion,
                pairingToken = "token",
            ),
        )
        assertFalse(
            WechatUiHookActivation.shouldInstall(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion.copy(versionCode = 3084L),
                pairingToken = "token",
            ),
        )
        assertTrue(
            WechatUiHookActivation.shouldInstall(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion,
                pairingToken = null,
            ),
        )
    }
}
