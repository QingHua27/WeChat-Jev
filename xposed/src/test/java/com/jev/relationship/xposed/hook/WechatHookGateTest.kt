package com.jev.relationship.xposed.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatHookGateTest {
    private val supportedVersion = WechatVersion(
        versionName = "8.0.72",
        versionCode = 3085L,
    )

    @Test
    fun `accepts supported main process and version`() {
        assertTrue(
            WechatHookGate.accepts(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion,
            ),
        )
    }

    @Test
    fun `rejects unsupported package`() {
        assertFalse(
            WechatHookGate.accepts(
                packageName = "com.example.other",
                processName = "com.tencent.mm",
                version = supportedVersion,
            ),
        )
    }

    @Test
    fun `rejects child process`() {
        assertFalse(
            WechatHookGate.accepts(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm:push",
                version = supportedVersion,
            ),
        )
    }

    @Test
    fun `rejects version name mismatch`() {
        assertFalse(
            WechatHookGate.accepts(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion.copy(versionName = "8.0.71"),
            ),
        )
    }

    @Test
    fun `rejects version code mismatch`() {
        assertFalse(
            WechatHookGate.accepts(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                version = supportedVersion.copy(versionCode = 3084L),
            ),
        )
    }

    @Test
    fun `rejects null package or process`() {
        assertFalse(WechatHookGate.accepts(null, "com.tencent.mm", supportedVersion))
        assertFalse(WechatHookGate.accepts("com.tencent.mm", null, supportedVersion))
    }
}
