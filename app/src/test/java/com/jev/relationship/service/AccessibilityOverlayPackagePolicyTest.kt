package com.jev.relationship.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityOverlayPackagePolicyTest {
    private val policy = AccessibilityOverlayPackagePolicy("com.tencent.mm")

    @Test
    fun `transient non wechat event keeps snapshot while active window is wechat`() {
        policy.shouldClearSnapshot(
            eventPackage = "com.tencent.mm",
            activeWindowPackage = "com.tencent.mm",
            nowMs = 0L,
        )
        assertFalse(
            policy.shouldClearSnapshot(
                eventPackage = "com.android.inputmethod.latin",
                activeWindowPackage = "com.android.inputmethod.latin",
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun `switching active window away from wechat clears snapshot`() {
        policy.shouldClearSnapshot(
            eventPackage = "com.tencent.mm",
            activeWindowPackage = "com.tencent.mm",
            nowMs = 0L,
        )
        assertTrue(
            policy.shouldClearSnapshot(
                eventPackage = "com.jev.relationship",
                activeWindowPackage = "com.jev.relationship",
                nowMs = 3_000L,
            ),
        )
    }

    @Test
    fun `active wechat window is accepted when event comes from another package`() {
        assertTrue(
            policy.isTargetWindow(
                eventPackage = "com.android.inputmethod.latin",
                activeWindowPackage = "com.tencent.mm",
            ),
        )
    }
}
