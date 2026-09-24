package com.jev.relationship.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDragTrackerTest {
    @Test
    fun `tap is treated as click`() {
        val tracker = OverlayDragTracker(touchSlopPx = 8f)

        tracker.onDown(100f, 200f)
        tracker.onMove(105f, 204f)

        assertTrue(tracker.onUp())
        assertFalse(tracker.wasDragging)
    }

    @Test
    fun `movement beyond touch slop is treated as drag`() {
        val tracker = OverlayDragTracker(touchSlopPx = 8f)

        tracker.onDown(100f, 200f)
        assertEquals(20f to 12f, tracker.onMove(120f, 212f))

        assertFalse(tracker.onUp())
        assertTrue(tracker.wasDragging)
    }
}
