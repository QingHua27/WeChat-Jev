package com.jev.relationship.xposed.ipc

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffPolicyTest {
    @Test
    fun returnsCappedExponentialDelays() {
        assertEquals(250L, BackoffPolicy.delayMillis(1))
        assertEquals(500L, BackoffPolicy.delayMillis(2))
        assertEquals(1_000L, BackoffPolicy.delayMillis(3))
        assertEquals(30_000L, BackoffPolicy.delayMillis(20))
    }
}
