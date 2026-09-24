package com.jev.relationship.ipc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcVisibleChatConversationTest {
    @Test
    fun `visible message keeps local database id across bundle transport`() {
        val conversation = IpcVisibleChatConversation(
            conversationId = "room@chatroom",
            conversationText = "对方：收到",
            anchorText = "收到",
            anchorIsOutgoing = false,
            messages = listOf(IpcVisibleChatMessage("收到", false, 0, localMessageId = 77L)),
        )

        val decoded = IpcVisibleChatConversation.fromBundle(conversation.toBundle())

        assertEquals(77L, decoded.messages.single().localMessageId)
    }
}
