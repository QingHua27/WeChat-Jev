package com.jev.relationship.service

import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.domain.surface.AssistantSurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAssistantServiceLifecycleTest {
    @Test
    fun `overlay ready activates shared surface and realtime runtime`() {
        val surface = AssistantSurfaceCoordinator()
        var realtimeStarted = false
        val lifecycle = FloatingAssistantLifecycle(
            surfaceCoordinator = surface,
            onRealtimeStart = { realtimeStarted = true },
            onRealtimeStop = {},
        )

        lifecycle.onOverlayReady()

        assertEquals(AssistantSurfaceState.Ready, surface.state.value)
        assertTrue(realtimeStarted)
    }

    @Test
    fun `destroy stops realtime runtime and hides shared surface`() {
        val surface = AssistantSurfaceCoordinator().also { it.activate() }
        var realtimeStopped = false
        val lifecycle = FloatingAssistantLifecycle(
            surfaceCoordinator = surface,
            onRealtimeStart = {},
            onRealtimeStop = { realtimeStopped = true },
        )

        lifecycle.onOverlayReady()
        lifecycle.onDestroy()

        assertEquals(AssistantSurfaceState.Hidden, surface.state.value)
        assertTrue(realtimeStopped)
    }
}
