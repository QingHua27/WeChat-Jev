package com.jev.relationship.xposed.hook

import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.MessageSender

data class WechatMessageSnapshot(
    val type: Int,
    val content: String?,
    val talker: String?,
    val isOutgoing: Boolean,
    val createTime: Long,
    val localMessageId: Long,
    val serverMessageId: Long,
)

object WechatMessageMapper {
    private const val TEXT_TYPE = 1
    private const val MILLIS_THRESHOLD = 1_000_000_000_000L
    private const val SOURCE_CLASS = "com.tencent.mm.storage.h9#Cb"

    fun map(snapshot: WechatMessageSnapshot): CapturedMessage? {
        if (snapshot.type != TEXT_TYPE) return null

        val conversationId = snapshot.talker?.trim().orEmpty()
        val text = snapshot.content?.trim().orEmpty()
        if (conversationId.isBlank() || !com.jev.relationship.ipc.ChatTextPolicy.isDialogue(text)) return null

        val messageNumericId = when {
            snapshot.localMessageId > 0L -> snapshot.localMessageId
            snapshot.serverMessageId > 0L -> snapshot.serverMessageId
            else -> return null
        }
        val timestampMs = if (snapshot.createTime < MILLIS_THRESHOLD) {
            snapshot.createTime * 1_000L
        } else {
            snapshot.createTime
        }

        return CapturedMessage(
            conversationId = conversationId,
            sender = if (snapshot.isOutgoing) MessageSender.SELF else MessageSender.CONTACT,
            text = text,
            timestampMs = timestampMs,
            sourcePackage = IpcProtocol.WECHAT_PACKAGE,
            sourceClass = SOURCE_CLASS,
            isOutgoing = snapshot.isOutgoing,
            messageId = "wechat-8.0.72-$messageNumericId",
        )
    }
}
