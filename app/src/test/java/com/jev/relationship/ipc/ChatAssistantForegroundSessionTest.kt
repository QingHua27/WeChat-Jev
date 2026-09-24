package com.jev.relationship.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ChatAssistantForegroundSessionTest {
    @Test
    fun `analysis stays foreground until every pending request finishes`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        val foreground = ChatAssistantForegroundSession(service)
        try {
            foreground.acquire("first")
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertFalse(shadowOf(service).isForegroundStopped)

            foreground.acquire("second")
            foreground.release("first")
            foreground.release("missing")
            assertFalse(shadowOf(service).isForegroundStopped)

            foreground.release("second")
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally {
            foreground.clear()
            controller.destroy()
        }
    }

    @Test
    fun `disconnect removes the analysis notification and permits another request`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        val foreground = ChatAssistantForegroundSession(service)
        try {
            foreground.acquire("old")
            foreground.clear()
            assertTrue(shadowOf(service).isForegroundStopped)

            foreground.acquire("new")
            assertFalse(shadowOf(service).isForegroundStopped)
            foreground.release("old")
            assertFalse(shadowOf(service).isForegroundStopped)
            foreground.release("new")
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally {
            foreground.clear()
            controller.destroy()
        }
    }

    class TestService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
}
