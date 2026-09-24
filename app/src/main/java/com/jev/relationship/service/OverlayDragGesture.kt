package com.jev.relationship.service

import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

internal fun Modifier.overlayDragGesture(
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit = {},
    onClick: () -> Unit,
): Modifier = semantics {
    onClick {
        onClick()
        true
    }
}.pointerInput(Unit) {
    awaitEachGesture {
        val tracker = OverlayDragTracker(touchSlopPx = 8f)
        val down = awaitFirstDown(requireUnconsumed = false)
        tracker.onDown(down.position.x, down.position.y)
        var dragStarted = false
        var lastDx = 0f
        var lastDy = 0f

        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull() ?: break
            if (change.changedToUp()) {
                val clicked = tracker.onUp() && !dragStarted
                onDragEnd()
                if (clicked) onClick()
                break
            }
            if (!change.pressed) {
                tracker.onCancel()
                onDragEnd()
                break
            }
            val total = tracker.onMove(change.position.x, change.position.y) ?: continue
            if (!dragStarted) {
                dragStarted = true
                onDragStart()
            }
            onDrag(total.first - lastDx, total.second - lastDy)
            lastDx = total.first
            lastDy = total.second
            change.consume()
        }
    }
}
