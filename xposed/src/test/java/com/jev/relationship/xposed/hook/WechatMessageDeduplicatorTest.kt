package com.jev.relationship.xposed.hook

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatMessageDeduplicatorTest {
    @Test
    fun `accepts first id and rejects repeated id`() {
        val deduplicator = WechatMessageDeduplicator(capacity = 4)

        assertTrue(deduplicator.shouldEmit("message-1"))
        assertFalse(deduplicator.shouldEmit("message-1"))
    }

    @Test
    fun `evicts oldest id when capacity is exceeded`() {
        val deduplicator = WechatMessageDeduplicator(capacity = 2)

        assertTrue(deduplicator.shouldEmit("message-1"))
        assertTrue(deduplicator.shouldEmit("message-2"))
        assertTrue(deduplicator.shouldEmit("message-3"))
        assertTrue(deduplicator.shouldEmit("message-1"))
        assertFalse(deduplicator.shouldEmit("message-3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non positive capacity`() {
        WechatMessageDeduplicator(capacity = 0)
    }

    @Test
    fun `accepts a concurrent id only once`() {
        val deduplicator = WechatMessageDeduplicator(capacity = 8)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val results = (1..100).map {
                executor.submit<Boolean> { deduplicator.shouldEmit("concurrent-id") }
            }.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }
}
