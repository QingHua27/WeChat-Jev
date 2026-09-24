package com.jev.relationship.service

import kotlin.math.roundToInt

internal class OverlayDragFrameState(
    initialX: Int,
    initialY: Int,
    private val maxX: Int,
    private val maxY: Int,
    private val xDirection: Int = 1,
) {
    private var x = initialX.toFloat()
    private var y = initialY.toFloat()

    fun reset(actualX: Int, actualY: Int) {
        x = actualX.toFloat()
        y = actualY.toFloat()
    }

    fun add(dx: Float, dy: Float) {
        x = (x + dx * xDirection).coerceIn(0f, maxX.toFloat())
        y = (y + dy).coerceIn(0f, maxY.toFloat())
    }

    fun rounded(): Pair<Int, Int> =
        x.roundToInt().coerceIn(0, maxX) to y.roundToInt().coerceIn(0, maxY)
}
