package com.jev.relationship.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragFrameStateTest {
    @Test
    fun `multiple pointer deltas are coalesced into one target position`() {
        val state = OverlayDragFrameState(initialX = 10, initialY = 20, maxX = 100, maxY = 100)

        state.add(0.4f, 0.4f)
        state.add(0.4f, 0.4f)
        state.add(0.4f, 0.4f)

        assertEquals(11 to 21, state.rounded())
    }

    @Test
    fun `drag target is clamped to the window bounds`() {
        val state = OverlayDragFrameState(initialX = 10, initialY = 20, maxX = 100, maxY = 100)

        state.add(500f, 500f)

        assertEquals(100 to 100, state.rounded())
    }
}
