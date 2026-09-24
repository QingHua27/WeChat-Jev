package com.jev.relationship.service

import kotlin.math.abs

/** Separates a short tap from an intentional drag without changing click behavior. */
internal class OverlayDragTracker(
    private val touchSlopPx: Float,
) {
    private var active = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    var wasDragging: Boolean = false
        private set

    val isDragging: Boolean
        get() = dragging

    fun onDown(x: Float, y: Float) {
        active = true
        dragging = false
        wasDragging = false
        downX = x
        downY = y
    }

    fun onMove(x: Float, y: Float): Pair<Float, Float>? {
        if (!active) return null
        val dx = x - downX
        val dy = y - downY
        if (!dragging && (abs(dx) >= touchSlopPx || abs(dy) >= touchSlopPx)) {
            dragging = true
        }
        return if (dragging) dx to dy else null
    }

    fun onUp(): Boolean {
        if (!active) return false
        wasDragging = dragging
        val isClick = !dragging
        active = false
        return isClick
    }

    fun onCancel() {
        active = false
        wasDragging = dragging
    }
}
