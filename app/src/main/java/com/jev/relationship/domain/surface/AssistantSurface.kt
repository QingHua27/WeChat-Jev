package com.jev.relationship.domain.surface

import com.jev.relationship.core.model.AnalysisResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AssistantSurfaceState {
    data object Hidden : AssistantSurfaceState
    data object Ready : AssistantSurfaceState
    data class PermissionRequired(val permission: String) : AssistantSurfaceState
    data class Visible(
        val emotion: String,
        val primaryIntent: String,
        val riskLevel: Int,
    ) : AssistantSurfaceState
    data class Error(val message: String) : AssistantSurfaceState
}

class AssistantSurfaceCoordinator {
    private val _state = MutableStateFlow<AssistantSurfaceState>(AssistantSurfaceState.Hidden)
    val state: StateFlow<AssistantSurfaceState> = _state.asStateFlow()

    fun activate() {
        _state.value = AssistantSurfaceState.Ready
    }

    fun show(analysis: AnalysisResult) {
        val primaryIntent = analysis.intents.maxByOrNull { it.confidence }?.name ?: "暂未识别主要意图"
        _state.value = AssistantSurfaceState.Visible(
            emotion = analysis.emotion,
            primaryIntent = primaryIntent,
            riskLevel = analysis.riskLevel,
        )
    }

    fun requirePermission(permission: String) {
        _state.value = AssistantSurfaceState.PermissionRequired(permission)
    }

    fun hide() {
        _state.value = AssistantSurfaceState.Hidden
    }
}
