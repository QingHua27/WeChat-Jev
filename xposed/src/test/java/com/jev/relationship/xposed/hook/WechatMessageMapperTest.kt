package com.jev.relationship.xposed.hook

import com.jev.relationship.ipc.CapturedMessageValidator
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.MessageSender
import com.jev.relationship.ipc.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatMessageMapperTest {
    @Test
    fun `maps text message with stable metadata`() {
        val message = WechatMessageMapper.map(
            snapshot(
                content = "  我们晚点聊？  ",
                talker = "  chat-1  ",
                createTime = 1_700_000_000L,
                localMessageId = 42L,
            ),
        )

        assertNotNull(message)
        assertEquals("我们晚点聊？", message?.text)
        assertEquals("chat-1", message?.conversationId)
        assertEquals(MessageSender.CONTACT, message?.sender)
        assertEquals(false, message?.isOutgoing)
        assertEquals(1_700_000_000_000L, message?.timestampMs)
        assertEquals(IpcProtocol.WECHAT_PACKAGE, message?.sourcePackage)
        assertEquals("com.tencent.mm.storage.h9#Cb", message?.sourceClass)
        assertEquals("wechat-8.0.72-42", message?.messageId)
    }

    @Test
    fun `maps outgoing message to self`() {
        val message = WechatMessageMapper.map(snapshot(isOutgoing = true))

        assertEquals(MessageSender.SELF, message?.sender)
        assertTrue(message?.isOutgoing == true)
    }

    @Test
    fun `rejects non text message`() {
        assertNull(WechatMessageMapper.map(snapshot(type = 3)))
    }

    @Test
    fun `rejects blank content or conversation`() {
        assertNull(WechatMessageMapper.map(snapshot(content = " \n")))
        assertNull(WechatMessageMapper.map(snapshot(talker = "\t")))
        assertNull(WechatMessageMapper.map(snapshot(content = null)))
        assertNull(WechatMessageMapper.map(snapshot(talker = null)))
    }

    @Test
    fun `preserves millisecond timestamp`() {
        val timestamp = 1_700_000_000_123L

        assertEquals(
            timestamp,
            WechatMessageMapper.map(snapshot(createTime = timestamp))?.timestampMs,
        )
    }

    @Test
    fun `falls back to server id when local id is unavailable`() {
        val message = WechatMessageMapper.map(
            snapshot(localMessageId = 0L, serverMessageId = 99L),
        )

        assertEquals("wechat-8.0.72-99", message?.messageId)
    }

    @Test
    fun `rejects message when both ids are invalid`() {
        assertNull(
            WechatMessageMapper.map(
                snapshot(localMessageId = 0L, serverMessageId = -1L),
            ),
        )
    }

    @Test
    fun `leaves oversized body for shared validator`() {
        val message = WechatMessageMapper.map(
            snapshot(content = "x".repeat(IpcProtocol.MAX_TEXT_LENGTH + 1)),
        )

        assertNotNull(message)
        assertEquals(
            RejectReason.TEXT_TOO_LONG,
            CapturedMessageValidator.validate(message!!).reason,
        )
    }

    private fun snapshot(
        content: String? = "你好",
        talker: String? = "chat-1",
        type: Int = 1,
        isOutgoing: Boolean = false,
        createTime: Long = 1_700_000_000L,
        localMessageId: Long = 1L,
        serverMessageId: Long = 2L,
    ) = WechatMessageSnapshot(
        type = type,
        content = content,
        talker = talker,
        isOutgoing = isOutgoing,
        createTime = createTime,
        localMessageId = localMessageId,
        serverMessageId = serverMessageId,
    )
}
