package com.jev.relationship.xposed.hook

import android.os.Looper
import com.jev.relationship.ipc.LocalHistoryPage
import com.jev.relationship.ipc.LocalHistoryRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WechatHistoryRequestDispatcherTest {
    private var active: String? = null
    private val queries = mutableListOf<String>()
    private val queued = ArrayDeque<Runnable>()
    private val replies = mutableListOf<LocalHistoryPage>()
    private val dispatcher = WechatHistoryRequestDispatcher(
        activeTalker = { active },
        readPage = { request, talker ->
            queries += talker
            LocalHistoryPage(request.requestId, talker, complete = true)
        },
        worker = Executor { queued.addLast(it) },
    )

    @Test fun `request waits for the requested chat to resume`() {
        active = "old"
        dispatcher.read(LocalHistoryRequest("r", "new"), replies::add)
        assertTrue(replies.isEmpty())
        assertTrue(queued.isEmpty())
        active = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(replies.isEmpty())
        active = "new"
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        queued.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("new"), queries)
        assertEquals(1, replies.size)
        assertNull(replies.single().error)
        assertEquals("new", replies.single().conversationId)
    }

    @Test fun `unready chat fails within a bounded wait without reading another chat`() {
        active = "other"
        dispatcher.read(LocalHistoryRequest("r", "new"), replies::add)
        assertTrue(replies.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, replies.size)
        assertNotNull(replies.single().error)
        assertTrue(queries.isEmpty())
        assertTrue(queued.isEmpty())
    }

    @Test fun `switch while database work is queued rejects the old page`() {
        active = "old"
        dispatcher.read(LocalHistoryRequest("r", "old"), replies::add)
        active = "new"
        queued.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(replies.single().error)
        assertTrue(replies.single().records.isEmpty())
    }

    @Test fun `ready chat is read immediately and replies once`() {
        active = "ready"
        dispatcher.read(LocalHistoryRequest("r", "ready"), replies::add)
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("ready"), queries)
        assertNull(replies.single().error)
    }
}
