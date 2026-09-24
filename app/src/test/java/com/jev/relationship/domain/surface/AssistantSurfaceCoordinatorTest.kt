package com.jev.relationship.domain.surface

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.IntentProbability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSurfaceCoordinatorTest {
    @Test
    fun coordinatorStartsHiddenAndShowsReadOnlySummary() {
        val coordinator = AssistantSurfaceCoordinator()
        val analysis = AnalysisResult("不满", listOf(IntentProbability("希望被重视", 0.72)), 8, "先回应情绪")

        assertEquals(AssistantSurfaceState.Hidden, coordinator.state.value)
        coordinator.show(analysis)

        val state = coordinator.state.value
        assertTrue(state is AssistantSurfaceState.Visible)
        assertEquals("希望被重视", (state as AssistantSurfaceState.Visible).primaryIntent)
        assertEquals(8, state.riskLevel)
    }

    @Test
    fun coordinatorCanHideWithoutSendingAnything() {
        val coordinator = AssistantSurfaceCoordinator()

        coordinator.hide()

        assertEquals(AssistantSurfaceState.Hidden, coordinator.state.value)
    }

    @Test
    fun coordinatorCanActivateReadOnlyAssistantShell() {
        val coordinator = AssistantSurfaceCoordinator()

        coordinator.activate()

        assertEquals(AssistantSurfaceState.Ready, coordinator.state.value)
    }
}
