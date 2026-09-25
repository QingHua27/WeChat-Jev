package com.jev.relationship.xposed.ui

import android.content.Context
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WechatChatViewLocatorTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `native message recycler is selected instead of its larger matching wrapper`() {
        val root = FrameLayout(context)
        val wrapper = ChatListMarker(context)
        val recycler = FrameLayout(context)
        root.addView(wrapper)
        wrapper.addView(recycler)
        val locator = WechatChatViewLocator(ChatListMarker::class.java.name,
            resourceName = { if (it === recycler) "c9o" else null })

        assertEquals(recycler, locator.locate(root)?.chatList)
        assertEquals(wrapper, locator.locate(root)?.container)
    }

    @Test
    fun `finds the chat list and nearest usable parent`() {
        val root = FrameLayout(context)
        val container = FrameLayout(context)
        val chatList = ChatListMarker(context)
        root.addView(container)
        container.addView(chatList)

        val host = WechatChatViewLocator(ChatListMarker::class.java.name).locate(root)

        assertEquals(chatList, host?.chatList)
        assertEquals(container, host?.container)
    }

    @Test
    fun `returns null when the chat list is absent`() {
        val root = FrameLayout(context)

        assertNull(WechatChatViewLocator(ChatListMarker::class.java.name).locate(root))
    }

    @Test
    fun `returns null when more than one chat list is present`() {
        val root = FrameLayout(context)
        root.addView(ChatListMarker(context))
        root.addView(ChatListMarker(context))

        assertNull(WechatChatViewLocator(ChatListMarker::class.java.name).locate(root))
    }

    private class ChatListMarker(context: Context) : FrameLayout(context)
}
