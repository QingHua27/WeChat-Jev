package com.jev.relationship.service

import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator

internal class FloatingAssistantLifecycle(
    private val surfaceCoordinator: AssistantSurfaceCoordinator,
    private val onRealtimeStart: () -> Unit,
    private val onRealtimeStop: () -> Unit,
) {
    private var active = false

    fun onOverlayReady() {
        if (active) return
        active = true
        surfaceCoordinator.activate()
        onRealtimeStart()
    }

    fun onDestroy() {
        if (active) {
            onRealtimeStop()
            active = false
        }
        surfaceCoordinator.hide()
    }
}
