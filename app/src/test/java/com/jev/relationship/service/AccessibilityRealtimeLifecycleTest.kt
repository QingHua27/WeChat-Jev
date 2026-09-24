package com.jev.relationship.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityRealtimeLifecycleTest {
    @Test
    fun `starts realtime analysis when accessibility service connects`() {
        val events = mutableListOf<String>()
        val lifecycle = AccessibilityRealtimeLifecycle(
            onRealtimeStart = { events += "start" },
            onRealtimeStop = { events += "stop" },
        )

        lifecycle.onConnected()
        lifecycle.onConnected()

        assertEquals(listOf("start"), events)
    }

    @Test
    fun `stops realtime analysis when accessibility service is interrupted`() {
        val events = mutableListOf<String>()
        val lifecycle = AccessibilityRealtimeLifecycle(
            onRealtimeStart = { events += "start" },
            onRealtimeStop = { events += "stop" },
        )

        lifecycle.onConnected()
        lifecycle.onInterrupted()
        lifecycle.onInterrupted()

        assertEquals(listOf("start", "stop"), events)
    }
}
