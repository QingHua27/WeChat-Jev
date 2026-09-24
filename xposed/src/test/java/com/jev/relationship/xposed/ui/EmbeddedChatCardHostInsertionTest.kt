package com.jev.relationship.xposed.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcIntentProbability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EmbeddedChatCardHostInsertionTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `analysis is inserted below the matched message instead of the chat container`() {
        val root = FrameLayout(context)
        val messageParent = FrameLayout(context)
        val message = TextView(context).apply { text = "对方消息" }
        root.addView(messageParent)
        messageParent.addView(ChatListMarker(context))
        messageParent.addView(message)
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> WechatMessageAnchor(message, outgoing = false) },
        )

        host.onAnalysisResult(result())
        host.attach(root)

        val row = messageParent.getChildAt(1) as LinearLayout
        assertEquals(2, messageParent.childCount)
        assertEquals(message, row.getChildAt(0))
        assertTrue(row.getChildAt(1) is JevEmbeddedAnalysisCardView)
    }

    @Test
    fun `keeps one embedded analysis row below each matched message`() {
        val root = FrameLayout(context)
        val messageParent = FrameLayout(context)
        val firstMessage = TextView(context).apply { text = "第一条" }
        val secondMessage = TextView(context).apply { text = "第二条" }
        root.addView(messageParent)
        messageParent.addView(ChatListMarker(context))
        messageParent.addView(firstMessage)
        messageParent.addView(secondMessage)
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, result ->
                WechatMessageAnchor(
                    message = if (result.messageId == "message-1") firstMessage else secondMessage,
                    outgoing = false,
                )
            },
        )

        host.onAnalysisResult(result("message-1", "第一条"))
        host.onAnalysisResult(result("message-2", "第二条"))
        host.attach(root)

        assertEquals(3, messageParent.childCount)
        assertTrue(messageParent.getChildAt(1) is LinearLayout)
        assertTrue(messageParent.getChildAt(2) is LinearLayout)
        assertEquals(firstMessage, (messageParent.getChildAt(1) as LinearLayout).getChildAt(0))
        assertEquals(secondMessage, (messageParent.getChildAt(2) as LinearLayout).getChildAt(0))
    }

    @Test
    fun `old result cannot move to another occurrence of the same text after scrolling`() {
        val root = FrameLayout(context)
        val messageParent = FrameLayout(context)
        val firstMessage = TextView(context).apply { text = "好的" }
        val laterMessage = TextView(context).apply { text = "好的" }
        root.addView(messageParent)
        messageParent.addView(ChatListMarker(context))
        messageParent.addView(firstMessage)
        messageParent.addView(laterMessage)
        var target = firstMessage
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> WechatMessageAnchor(target, outgoing = false) },
        )
        host.onAnalysisResult(result(text = "好的"))
        host.attach(root)
        assertTrue(host.cardVisible)

        target = laterMessage
        host.renderNow()
        assertFalse(host.cardVisible)
        assertEquals(messageParent, laterMessage.parent)

        host.clear()
        host.onAnalysisResult(result(text = "好的"))
        assertTrue(host.cardVisible)
        assertTrue(laterMessage.parent is LinearLayout)
        host.destroy()
    }

    private fun result(messageId: String = "message-1", text: String = "对方消息") = IpcAnalysisResult(
        messageId = messageId,
        conversationHash = "conversation-hash",
        textHash = WechatMessageAnchorResolver.hash(text),
        isOutgoing = false,
        emotion = "平静",
        intents = listOf(IpcIntentProbability("沟通", 0.8)),
        riskLevel = 3,
        suggestion = "保持清晰。",
    )

    private class ChatListMarker(context: Context) : FrameLayout(context)
}
