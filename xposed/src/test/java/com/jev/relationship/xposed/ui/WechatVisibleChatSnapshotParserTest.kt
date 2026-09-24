package com.jev.relationship.xposed.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WechatVisibleChatSnapshotParserTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `snapshot carries the message id from its bound row`() {
        val root = FrameLayout(context).apply { layout(0, 0, 400, 800) }
        val list = ChatList(context).apply { layout(0, 0, 400, 800) }
        val bubble = TextView(context).apply {
            text = "群聊里的目标消息"
            layout(20, 100, 280, 160)
        }
        root.addView(list)
        list.addView(bubble)
        val parser = WechatVisibleChatSnapshotParser(
            locator = WechatChatViewLocator(ChatList::class.java.name),
            localMessageIdOf = { view -> if (view === bubble) 77L else null },
        )

        val snapshot = parser.parse(root, "room@chatroom", 400)

        assertNotNull(snapshot)
        assertEquals(77L, snapshot?.messages?.single()?.localMessageId)
    }

    private class ChatList(context: Context) : FrameLayout(context)
}
