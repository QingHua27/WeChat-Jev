package com.jev.relationship.service

import android.view.View
import android.view.WindowManager

/** Coalesces high-frequency pointer deltas into one WindowManager update per frame. */
internal class OverlayWindowDragController(
    private val view: View,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val xDirection: Int = 1,
    private val maxXOverride: (() -> Int)? = null,
) {
    private var position: OverlayDragFrameState? = null
    private var framePosted = false
    private var dragging = false

    fun start() {
        dragging = true
        position = OverlayDragFrameState(
            initialX = params.x,
            initialY = params.y,
            maxX = maxX(),
            maxY = maxY(),
            xDirection = xDirection,
        )
    }

    fun moveBy(dx: Float, dy: Float) {
        if (!dragging) start()
        (position ?: return).add(dx, dy)
        postFrame()
    }

    fun end() {
        applyPending()
        dragging = false
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        view.postOnAnimation {
            framePosted = false
            applyPending()
            val (nextX, nextY) = (position ?: return@postOnAnimation).rounded()
            if (dragging && (params.x != nextX || params.y != nextY)) {
                postFrame()
            }
        }
    }

    private fun applyPending() {
        val (nextX, nextY) = (position ?: return).rounded()
        if (params.x == nextX && params.y == nextY) return
        params.x = nextX
        params.y = nextY
        if (view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun maxX(): Int =
        (maxXOverride?.invoke()
            ?: (screenWidth - view.width.coerceAtLeast(params.width.takeIf { it > 0 } ?: 0)))
            .coerceAtLeast(0)

    private fun maxY(): Int =
        (screenHeight - view.height.coerceAtLeast(1)).coerceAtLeast(0)

}
