package com.jev.relationship.xposed.ui

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcIntentProbability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WechatMessageAnchorResolverTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `matches visible message text and places card below incoming bubble`() {
        val container = container()
        val message = text("对方消息", 20, 100, 180, 150)
        container.addView(message)

        val anchor = WechatMessageAnchorResolver().resolve(container, result("对方消息", false))

        assertSame(message, anchor?.message)
        assertFalse(anchor?.outgoing == true)
    }

    @Test
    fun `batch resolution returns the same anchors as individual resolution`() {
        val container = container()
        val first = text("第一条", 20, 100, 180, 150)
        val second = text("第二条", 20, 200, 180, 250)
        val outgoing = text("我发的", 250, 300, 390, 350)
        container.addView(first)
        container.addView(second)
        container.addView(outgoing)
        val resolver = WechatMessageAnchorResolver()
        val results = listOf(
            result("第一条", false, messageId = "first"),
            result("第二条", false, messageId = "second"),
            result("我发的", true, messageId = "outgoing"),
        )

        val expected = results.associate { it.messageId to resolver.resolve(container, it) }
        val actual = resolver.resolveAll(container, results)

        assertEquals(expected.filterValues { it != null }, actual)
    }

    @Test
    fun `matches outgoing side and moves card above when below would overflow`() {
        val container = container(height = 300)
        val message = text("我的消息", 250, 220, 390, 270)
        container.addView(message)

        val anchor = WechatMessageAnchorResolver().resolve(container, result("我的消息", true))

        assertSame(message, anchor?.message)
        assertTrue(anchor?.outgoing == true)
    }

    @Test
    fun `ignores editor and mismatched text`() {
        val container = container()
        val editor = EditText(context).also {
            it.setText("目标消息")
            it.layout(20, 100, 180, 150)
        }
        container.addView(editor)

        assertNull(WechatMessageAnchorResolver().resolve(container, result("目标消息", false)))
        assertNull(WechatMessageAnchorResolver().resolve(container, result("其他消息", false)))
    }

    @Test
    fun `matches message content description when text is not exposed`() {
        val container = container()
        val message = TextView(context).also {
            it.contentDescription = "目标消息"
            it.layout(20, 100, 180, 150)
        }
        container.addView(message)

        val anchor = WechatMessageAnchorResolver().resolve(container, result("目标消息", false))

        assertSame(message, anchor?.message)
    }

    @Test
    fun `does not discard ordinary text when another view uses the bubble id`() {
        val container = container()
        val ordinary = text("普通文本消息", 20, 100, 220, 150)
        val voiceDuration = text("20\"", 20, 200, 120, 250)
        container.addView(ordinary)
        container.addView(voiceDuration)

        val resolver = WechatMessageAnchorResolver(resourceName = { view ->
            if (view === voiceDuration) "c3u" else null
        })

        val anchor = resolver.resolve(container, result("普通文本消息", false))

        assertSame(ordinary, anchor?.message)
    }

    @Test
    fun `never guesses a different bubble when exact text is absent`() {
        val container = container()
        val first = text("普通文本消息", 20, 100, 220, 150)
        val second = text("另一条文本消息", 20, 200, 240, 250)
        container.addView(first)
        container.addView(second)

        val resolver = WechatMessageAnchorResolver(resourceName = { view ->
            if (view === first || view === second) "c3u" else null
        })

        val anchor = resolver.resolve(
            container,
            result("未暴露文本", false, occurrence = 1),
        )

        assertNull(anchor)
    }

    @Test
    fun `does not guess a row for unreadable virtual text`() {
        val container = container()
        val firstRow = row(100)
        val secondRow = row(200)
        container.addView(firstRow)
        container.addView(secondRow)

        val resolver = WechatMessageAnchorResolver(resourceName = { view ->
            when (view) {
                firstRow, secondRow -> "c78"
                firstRow.getChildAt(0), secondRow.getChildAt(0) -> "c3r"
                else -> null
            }
        })

        val anchor = resolver.resolve(
            container,
            result("虚拟文本", false, occurrence = 1),
        )

        assertNull(anchor)
    }

    @Test
    fun `uses the lowest matching visible candidate when text repeats`() {
        val container = container()
        container.addView(text("重复", 20, 80, 180, 120))
        container.addView(text("重复", 20, 180, 180, 220))

        val anchor = WechatMessageAnchorResolver().resolve(container, result("重复", false, occurrence = 1))

        assertSame(container.getChildAt(1), anchor?.message)
    }

    @Test
    fun `resolves repeated text by occurrence so every result gets its own message`() {
        val container = container()
        container.addView(text("重复", 20, 80, 180, 120))
        container.addView(text("重复", 20, 180, 180, 220))

        val first = WechatMessageAnchorResolver().resolve(container, result("重复", false, occurrence = 0))
        val second = WechatMessageAnchorResolver().resolve(container, result("重复", false, occurrence = 1))

        assertSame(container.getChildAt(0), first?.message)
        assertSame(container.getChildAt(1), second?.message)
    }

    @Test
    fun `stable WeChat message id wins over viewport occurrence after recycling`() {
        val container = container()
        val earlier = text("重复", 20, 80, 180, 120)
        val target = text("重复", 20, 180, 180, 220)
        container.addView(earlier)
        container.addView(target)
        val ids = mapOf(earlier to 41L, target to 42L)

        val anchor = WechatMessageAnchorResolver(localMessageId = { ids[it] })
            .resolve(container, result("重复", false, occurrence = 0, messageId = "wechat-8.0.72-42"))

        assertSame(target, anchor?.message)
        assertEquals(42L, anchor?.localMessageId)
    }

    @Test
    fun `reads actual WeChat custom view text instead of requiring TextView`() {
        val container = container()
        val message = com.tencent.mm.ui.widget.MMNeat7extView(context).apply {
            value = "自定义气泡"
            layout(20, 100, 180, 150)
        }
        container.addView(message)
        assertSame(message, WechatMessageAnchorResolver().resolve(container, result("自定义气泡", false))?.message)
    }

    @Test
    fun `nested outgoing bubble uses container coordinates`() {
        val container = container()
        val row = FrameLayout(context).apply { layout(240, 100, 400, 200) }
        val message = text("自己的消息", 0, 0, 140, 50)
        row.addView(message)
        container.addView(row)
        assertNull(WechatMessageAnchorResolver().resolve(container, result("自己的消息", false)))
        assertSame(message, WechatMessageAnchorResolver().resolve(container, result("自己的消息", true))?.message)
    }

    private fun container(height: Int = 800) = FrameLayout(context).also {
        it.layout(0, 0, 400, height)
    }

    private fun text(value: String, left: Int, top: Int, right: Int, bottom: Int) =
        TextView(context).also {
            it.text = value
            it.layout(left, top, right, bottom)
            it.visibility = TextView.VISIBLE
        }

    private fun row(top: Int) = FrameLayout(context).also { row ->
        row.layout(0, top, 400, top + 60)
        row.addView(TextView(context).also { text ->
            text.layout(20, 0, 180, 50)
            text.visibility = TextView.VISIBLE
        })
    }

    private fun result(text: String, outgoing: Boolean, occurrence: Int = 0, messageId: String = "message-1") = IpcAnalysisResult(
        messageId = messageId,
        conversationHash = "conversation-hash",
        textHash = WechatMessageAnchorResolver.hash(text),
        isOutgoing = outgoing,
        messageOccurrence = occurrence,
        emotion = "平静",
        intents = listOf(IpcIntentProbability("沟通", 0.8)),
        riskLevel = 3,
        suggestion = "保持清晰。",
    )
}
