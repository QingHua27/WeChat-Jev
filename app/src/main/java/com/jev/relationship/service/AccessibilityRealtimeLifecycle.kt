package com.jev.relationship.service

internal class AccessibilityRealtimeLifecycle(
    private val onRealtimeStart: () -> Unit,
    private val onRealtimeStop: () -> Unit,
) {
    private var active = false

    fun onConnected() {
        if (active) return
        active = true
        onRealtimeStart()
    }

    fun onInterrupted() {
        if (!active) return
        active = false
        onRealtimeStop()
    }
}
