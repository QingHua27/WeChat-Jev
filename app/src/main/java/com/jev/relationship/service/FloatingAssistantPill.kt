package com.jev.relationship.service

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jev.relationship.domain.surface.AssistantSurfaceState

@Composable
fun FloatingAssistantPill(
    state: AssistantSurfaceState,
    onOpen: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    if (state is AssistantSurfaceState.Hidden) return
    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.overlayDragGesture(
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onClick = {},
        ),
    ) {
        TextButton(onClick = onOpen, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                when (state) {
                    AssistantSurfaceState.Ready -> "Jev 助手已开启"
                    is AssistantSurfaceState.Visible -> "Jev · ${state.primaryIntent} ${state.riskLevel}/10"
                    is AssistantSurfaceState.PermissionRequired -> "需要开启权限"
                    is AssistantSurfaceState.Error -> "Jev 暂不可用"
                    AssistantSurfaceState.Hidden -> ""
                },
            )
        }
    }
}
