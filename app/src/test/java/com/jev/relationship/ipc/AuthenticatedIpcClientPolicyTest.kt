package com.jev.relationship.ipc

import android.os.Binder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthenticatedIpcClientPolicyTest {
    @Test
    fun `assistant request requires authenticated binder from WeChat package`() {
        val authenticated = Binder()
        assertTrue(AuthenticatedIpcClientPolicy.allows(
            authenticatedBinder = authenticated,
            replyBinder = authenticated,
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        ))
        assertFalse(AuthenticatedIpcClientPolicy.allows(
            authenticatedBinder = authenticated,
            replyBinder = Binder(),
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        ))
        assertFalse(AuthenticatedIpcClientPolicy.allows(
            authenticatedBinder = authenticated,
            replyBinder = authenticated,
            callerPackages = setOf("com.example.unpaired"),
        ))
        assertFalse(AuthenticatedIpcClientPolicy.allows(
            authenticatedBinder = null,
            replyBinder = authenticated,
            callerPackages = setOf(IpcProtocol.WECHAT_PACKAGE),
        ))
    }
}
