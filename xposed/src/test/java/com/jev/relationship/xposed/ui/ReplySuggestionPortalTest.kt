package com.jev.relationship.xposed.ui

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class ReplySuggestionPortalTest {
    @Test fun `animation reverses and cancellation cannot run a stale completion or move native views`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val row = TextView(activity).apply { text = "建议回复" }
        val target = TextView(activity).apply { text = "AI分析" }
        root.addView(row, FrameLayout.LayoutParams(300, 56).apply { topMargin = 400 })
        root.addView(target, FrameLayout.LayoutParams(64, 48))
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        root.layout(0, 0, 400, 800)
        row.layout(0, 400, 300, 456)
        target.layout(30, 0, 94, 48)
        val portal = ReplySuggestionPortal()
        var staleCompletions = 0
        var restored = 0
        try {
            assertTrue(portal.animate(row, target, true) { staleCompletions++ })
            // Drive the animator deterministically; Robolectric's vsync clock can advance
            // faster than its looper clock, so a wall-time sleep is not a midpoint.
            val running = ReplySuggestionPortal::class.java.getDeclaredField("animator").apply {
                isAccessible = true
            }.get(portal) as android.animation.ValueAnimator
            running.currentPlayTime = 180
            assertTrue(portal.isRunning)
            assertTrue(portal.animate(row, target, false) { restored++ })
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertFalse(portal.isRunning)
            assertEquals(0, staleCompletions)
            assertEquals(1, restored)
            assertSame(root, row.parent)
            assertSame(root, target.parent)
            portal.animate(row, target, true) { staleCompletions++ }
            portal.cancel()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertFalse(portal.isRunning)
            assertEquals(0, staleCompletions)
            assertEquals(2, root.childCount)
        } finally {
            portal.cancel()
            activity.finish()
        }
    }
}
