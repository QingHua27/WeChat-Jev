package com.jev.relationship.xposed.ui

import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcAnalysisSection
import com.jev.relationship.ipc.IpcIntentProbability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JevEmbeddedAnalysisCardViewTest {
    @Test
    fun displaysActualModelFailureRatherThanAskingForMoreContext() {
        val view = JevEmbeddedAnalysisCardView(RuntimeEnvironment.getApplication())
        view.render(IpcAnalysisResult("message", "chat", "text", false, emotion = "平静", intents = emptyList(),
            riskLevel = 0, suggestion = "", detailIntention = "模型服务限流或额度不足（HTTP 429），请稍后重试。", detailContextual = false))
        val text = (view.getChildAt(0) as TextView).text.toString()
        assertTrue(text.contains("HTTP 429"))
        assertFalse(text.contains("上下文意图暂不可用"))
    }

    @Test
    fun allowsFullAnalysisWithoutEllipsis() {
        val view = JevEmbeddedAnalysisCardView(RuntimeEnvironment.getApplication())
        val content = view.getChildAt(0) as TextView

        assertEquals(Int.MAX_VALUE, content.maxLines)
        assertNull(content.ellipsize)
    }

    @Test
    fun rendersConciseIntentionWithoutJevHeading() {
        val view = JevEmbeddedAnalysisCardView(RuntimeEnvironment.getApplication())
        view.render(
            IpcAnalysisResult(
                messageId = "message-1",
                conversationHash = "conversation",
                textHash = "text",
                isOutgoing = false,
                emotion = "平静",
                intents = emptyList(),
                sections = listOf(IpcAnalysisSection("危机是否解除？", listOf(IpcIntentProbability("Yes", 0.94), IpcIntentProbability("No", 0.06))), IpcAnalysisSection("建议动作", text = "立即停止模型调用，不要画蛇添足。")),
                riskLevel = 1,
                suggestion = "旧建议不应成为主内容。",
                detailSummary = "对方在认真追问你是否愿意回应。",
                detailIntention = "希望得到明确答复。",
                detailContextual = true,
                detailEvidence = listOf("连续追问", "没有转移话题"),
                detailAction = "先直接回答，再补充原因。",
                detailReply = "我看到了，我们把这件事说清楚。",
            ),
        )

        val text = (view.getChildAt(0) as TextView).text.toString()
        assertEquals("解析：希望得到明确答复。", text)
        assertFalse(text.contains("94%"))
        assertFalse(text.contains("危险等级"))
        assertFalse(text.contains("建议动作"))
        assertFalse(text.contains("分享信息"))
    }
}
