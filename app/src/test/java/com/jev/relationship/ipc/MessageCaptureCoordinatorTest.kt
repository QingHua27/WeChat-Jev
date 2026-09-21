package com.jev.relationship.ipc

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageCaptureCoordinatorTest {
    @Test
    fun `only accepted messages are emitted`() = runTest {
        val coordinator = MessageCaptureCoordinator()
        coordinator.activate()
        val emitted = async { coordinator.events.first() }
        runCurrent()

        val result = coordinator.submit(message(text = "hello"))

        assertTrue(result.accepted)
        assertEquals(message(text = "hello"), emitted.await())
    }

    @Test
    fun `rejected messages are not emitted`() = runTest {
        val coordinator = MessageCaptureCoordinator()
        coordinator.activate()
        val emitted = async { coordinator.events.first() }

        val result = coordinator.submit(message(text = "   "))
        runCurrent()

        assertFalse(result.accepted)
        assertFalse(emitted.isCompleted)
        emitted.cancel()
    }

    @Test
    fun `reset clears the latest conversation without emitting an event`() = runTest {
        val coordinator = MessageCaptureCoordinator()
        coordinator.activate()
        coordinator.submit(message(text = "before reset"))
        val emitted = async { coordinator.events.first() }

        coordinator.reset()
        runCurrent()

        assertEquals(null, coordinator.latestConversation())
        assertFalse(emitted.isCompleted)
        emitted.cancel()
    }

    private fun message(text: String) = CapturedMessage(
        conversationId = "chat-1",
        sender = MessageSender.CONTACT,
        text = text,
        timestampMs = 1_000L,
        sourcePackage = IpcProtocol.WECHAT_PACKAGE,
        sourceClass = null,
        isOutgoing = false,
        messageId = "message-1",
    )
}
