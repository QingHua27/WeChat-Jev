package com.jev.relationship.xposed.ui

import android.app.Activity
import android.widget.EditText
import android.widget.TextView
import com.jev.relationship.ipc.ChatAssistantRequest
import com.jev.relationship.ipc.ChatAssistantResult
import com.jev.relationship.ipc.ChatAssistantTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class ChatAssistantDialogTest {
    @Test
    fun `keyboard reduces dialog height and hiding it restores height without losing draft`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = ChatAssistantDialog(activity, "alice", "Alice",
            requestAssistant = { request, reply ->
                reply(ChatAssistantResult(request.requestId, "alice", emptyList()))
                true
            }, cancelRequest = {})
        try {
            dialog.open()
            val decor = dialog.window!!.decorView
            val originalHeight = dialog.window!!.attributes.height
            val input = findViews(dialog, EditText::class.java).single()
            input.setText("正在输入的追问")
            val availableHeight = originalHeight / 2
            shadowOf(activity.windowManager.defaultDisplay).setHeight(availableHeight)
            decor.viewTreeObserver.dispatchOnGlobalLayout()
            assertTrue("Dialog must fit above the keyboard", dialog.window!!.attributes.height <= availableHeight)
            assertEquals("正在输入的追问", input.text.toString())
            shadowOf(activity.windowManager.defaultDisplay).setHeight(originalHeight * 2)
            decor.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals(originalHeight, dialog.window!!.attributes.height)
            assertEquals("正在输入的追问", input.text.toString())
        } finally { dialog.dismiss(); activity.finish() }
    }

    @Test
    fun `failed stream removes provisional content and ignores late chunks after closing`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var sent: ChatAssistantRequest? = null
        var callback: ((ChatAssistantResult) -> Unit)? = null
        val cancelled = mutableListOf<String>()
        val dialog = ChatAssistantDialog(activity, "alice", "Alice",
            requestAssistant = { request, result -> sent = request; callback = result; true }, cancelRequest = cancelled::add)
        try {
            dialog.open()
            val partial = ChatAssistantResult(sent!!.requestId, "alice", listOf(ChatAssistantTurn("assistant", "unfinished draft", 1L)), isPartial = true)
            callback!!(partial)
            callback!!(ChatAssistantResult(sent!!.requestId, "alice", emptyList(), "network failed"))
            assertEquals(false, allText(dialog).contains("unfinished draft"))
            assertTrue(allText(dialog).contains("未保存"))
            findViews(dialog, TextView::class.java).single { it.text?.toString() == "重试" }.performClick()
            callback!!(partial.copy(requestId = sent!!.requestId))
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(sent!!.requestId), cancelled)
            callback!!(partial.copy(requestId = sent!!.requestId, turns = listOf(ChatAssistantTurn("assistant", "late chunk", 1L))))
            assertEquals(false, allText(dialog).contains("late chunk"))
        } finally { dialog.dismiss(); activity.finish() }
    }

    @Test
    fun `partial answer grows before final and keeps composer disabled`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var sent: ChatAssistantRequest? = null
        var callback: ((ChatAssistantResult) -> Unit)? = null
        val dialog = ChatAssistantDialog(activity, "alice", "Alice",
            requestAssistant = { request, result -> sent = request; callback = result; true }, cancelRequest = {})
        try {
            dialog.open()
            fun reply(text: String, partial: Boolean) = callback!!(ChatAssistantResult(sent!!.requestId, "alice",
                listOf(ChatAssistantTurn("assistant", text, 1L)), isPartial = partial))
            reply("first", true)
            assertTrue(allText(dialog).contains("first"))
            assertEquals(false, findViews(dialog, EditText::class.java).single().isEnabled)
            reply("first second", true)
            assertTrue(allText(dialog).contains("first second"))
            reply("complete answer", false)
            assertTrue(allText(dialog).contains("complete answer"))
            assertEquals(true, findViews(dialog, EditText::class.java).single().isEnabled)
            assertEquals(false, allText(dialog).contains("first second"))
        } finally { dialog.dismiss(); activity.finish() }
    }

    @Test
    fun `dialog opens with privacy disclosure and restores saved conversation before refreshing`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var sent: ChatAssistantRequest? = null
        var callback: ((ChatAssistantResult) -> Unit)? = null
        val dialog = ChatAssistantDialog(
            activity,
            conversationId = "alice",
            title = "Alice",
            requestAssistant = { request, result -> sent = request; callback = result; true },
            cancelRequest = {},
        )

        dialog.open()

        assertTrue(dialog.isShowing)
        assertEquals(null, sent?.question)
        assertEquals("alice", sent?.conversationId)
        assertEquals(true, sent?.resumeSession)
        assertTrue(allText(dialog).contains("默认发送近期及相关聊天片段和最近问答"))
        assertEquals(false, sent?.fullContext)
        assertTrue(dialog.window!!.attributes.gravity and android.view.Gravity.CENTER == android.view.Gravity.CENTER)

        callback!!(ChatAssistantResult(sent!!.requestId, "alice", listOf(ChatAssistantTurn("assistant", "她希望得到明确回应。", 1L))))
        assertTrue(allText(dialog).contains("她希望得到明确回应。"))
        assertEquals(android.view.Gravity.CENTER, dialog.window!!.attributes.gravity)
        findViews(dialog, TextView::class.java).single { it.text?.toString() == "完整分析" }.performClick()
        assertEquals(false, sent?.resumeSession)
        assertEquals(true, sent?.fullContext)
        assertEquals(null, sent?.question)
        callback!!(ChatAssistantResult(sent!!.requestId, "alice", emptyList(), "failed"))
        findViews(dialog, TextView::class.java).single { it.text?.toString() == "重试" }.performClick()
        assertEquals(true, sent?.fullContext)
        dialog.dismiss()
    }

    @Test
    fun `default report prompt is shown as a short local action label`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val requests = mutableListOf<ChatAssistantRequest>()
        val callbacks = mutableListOf<(ChatAssistantResult) -> Unit>()
        val dialog = ChatAssistantDialog(
            activity,
            conversationId = "alice",
            title = "Alice",
            requestAssistant = { request, result -> requests += request; callbacks += result; true },
            cancelRequest = {},
        )
        dialog.open()

        callbacks.last()(ChatAssistantResult(
            requests.last().requestId,
            "alice",
            listOf(
                ChatAssistantTurn(ChatAssistantTurn.ROLE_USER, ChatAssistantRequest.DEFAULT_ANALYSIS_PROMPT, 1L),
                ChatAssistantTurn(ChatAssistantTurn.ROLE_ASSISTANT, "收到，你们在确认见面安排。你有什么疑问？", 2L),
            ),
        ))
        assertTrue(allText(dialog).contains("开始上传并分析"))
        assertEquals(false, allText(dialog).contains(ChatAssistantRequest.DEFAULT_ANALYSIS_PROMPT))

        findViews(dialog, TextView::class.java).single { it.text?.toString() == "完整分析" }.performClick()
        assertEquals(null, requests.last().question)
        assertEquals(false, requests.last().resumeSession)
        callbacks.last()(ChatAssistantResult(
            requests.last().requestId,
            "alice",
            listOf(
                ChatAssistantTurn(ChatAssistantTurn.ROLE_USER, ChatAssistantRequest.DEFAULT_ANALYSIS_PROMPT, 3L),
                ChatAssistantTurn(ChatAssistantTurn.ROLE_ASSISTANT, "收到，对方想确认时间。你有什么疑问？", 4L),
            ),
        ))
        assertTrue(allText(dialog).contains("开始上传并分析"))
        assertEquals(false, allText(dialog).contains(ChatAssistantRequest.DEFAULT_ANALYSIS_PROMPT))
        dialog.dismiss()
    }

    @Test
    fun `follow-up question is sent and response is appended in order`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val requests = mutableListOf<ChatAssistantRequest>()
        val callbacks = mutableListOf<(ChatAssistantResult) -> Unit>()
        val dialog = ChatAssistantDialog(
            activity,
            conversationId = "alice",
            title = "Alice",
            requestAssistant = { request, result -> requests += request; callbacks += result; true },
            cancelRequest = {},
        )
        dialog.open()
        callbacks.single()(ChatAssistantResult(requests.single().requestId, "alice", listOf(ChatAssistantTurn("assistant", "整体梗概。", 1L))))

        val input = findViews(dialog, EditText::class.java).single { it.hint?.toString() == "继续提问，不明白的可以直接问我" }
        input.setText("她说‘再看看’是什么意思？")
        findViews(dialog, TextView::class.java).single { it.text?.toString() == "发送" }.performClick()

        assertEquals("她说‘再看看’是什么意思？", requests.last().question)
        assertEquals(false, requests.last().resumeSession)
        callbacks.last()(ChatAssistantResult(requests.last().requestId, "alice", listOf(
            ChatAssistantTurn("assistant", "整体梗概。", 1L),
            ChatAssistantTurn("user", "她说‘再看看’是什么意思？", 2L),
            ChatAssistantTurn("assistant", "这句话本身比较含糊。", 3L),
        )))
        val rendered = allText(dialog)
        assertTrue(rendered.indexOf("整体梗概。") < rendered.indexOf("她说‘再看看’是什么意思？"))
        assertTrue(rendered.contains("这句话本身比较含糊。"))
        dialog.dismiss()
    }

    @Test
    fun `failed report can be retried and close cancels the pending request`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val requests = mutableListOf<ChatAssistantRequest>()
        val callbacks = mutableListOf<(ChatAssistantResult) -> Unit>()
        val cancelled = mutableListOf<String>()
        val dialog = ChatAssistantDialog(
            activity,
            conversationId = "alice",
            title = "Alice",
            requestAssistant = { request, result -> requests += request; callbacks += result; true },
            cancelRequest = cancelled::add,
        )
        dialog.open()
        callbacks.single()(ChatAssistantResult(requests.single().requestId, "alice", emptyList(), "理解模型暂时不可用"))

        findViews(dialog, TextView::class.java).single { it.text?.toString() == "重试" }.performClick()
        assertEquals(2, requests.size)
        assertEquals(null, requests.last().question)
        assertEquals(true, requests.last().resumeSession)
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(requests.last().requestId), cancelled)
    }

    private fun allText(dialog: ChatAssistantDialog): String = findViews(dialog, TextView::class.java)
        .mapNotNull { it.text?.toString() }
        .joinToString("\n")

    private fun <T : android.view.View> findViews(dialog: ChatAssistantDialog, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        fun visit(view: android.view.View) {
            if (type.isInstance(view)) type.cast(view)?.let(result::add)
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(dialog.window!!.decorView)
        return result
    }
}


