package com.jev.relationship.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatScopeTest {
    @Test
    fun matchesOnlyTheOfficialWechatPackage() {
        assertTrue(WechatScope.isSupported("com.tencent.mm"))
        assertFalse(WechatScope.isSupported("com.tencent.mm:push"))
        assertFalse(WechatScope.isSupported("com.tencent.mm.fake"))
        assertFalse(WechatScope.isSupported("com.jev.relationship"))
    }
}
