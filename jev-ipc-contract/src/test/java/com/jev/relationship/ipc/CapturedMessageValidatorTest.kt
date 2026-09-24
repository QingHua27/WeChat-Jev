package com.jev.relationship.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedMessageValidatorTest {
    @Test
    fun acceptsBoundedMessageFromWeChat() {
        val result = CapturedMessageValidator.validate(
            CapturedMessage(
                conversationId = "chat-1",
                sender = MessageSender.CONTACT,
                text = "我们晚点聊？",
                timestampMs = 1_000L,
                sourcePackage = IpcProtocol.WECHAT_PACKAGE,
                sourceClass = "com.tencent.mm.ui.chatting.ChattingUI",
                isOutgoing = false,
                messageId = "msg-1",
            ),
        )

        assertTrue(result.accepted)
        assertEquals(null, result.reason)
    }

    @Test
    fun rejectsBlankText() {
        val result = CapturedMessageValidator.validate(message(text = " \n"))

        assertEquals(RejectReason.BLANK_TEXT, result.reason)
    }

    @Test
    fun rejectsNonWeChatSource() {
        val result = CapturedMessageValidator.validate(
            message(sourcePackage = "com.example.other"),
        )

        assertEquals(RejectReason.SOURCE_PACKAGE_NOT_ALLOWED, result.reason)
    }

    @Test
    fun rejectsTextLongerThanProtocolLimit() {
        val result = CapturedMessageValidator.validate(
            message(text = "x".repeat(IpcProtocol.MAX_TEXT_LENGTH + 1)),
        )

        assertEquals(RejectReason.TEXT_TOO_LONG, result.reason)
    }

    @Test
    fun rejectsBatchLargerThanProtocolLimit() {
        val result = CapturedMessageValidator.validateBatch(
            List(IpcProtocol.MAX_BATCH_SIZE + 1) { message("$it") },
        )

        assertEquals(RejectReason.BATCH_TOO_LARGE, result.reason)
    }

    private fun message(
        text: String = "你好",
        sourcePackage: String = IpcProtocol.WECHAT_PACKAGE,
    ) = CapturedMessage(
        conversationId = "chat-1",
        sender = MessageSender.CONTACT,
        text = text,
        timestampMs = 1_000L,
        sourcePackage = sourcePackage,
        sourceClass = null,
        isOutgoing = false,
        messageId = null,
    )
}
