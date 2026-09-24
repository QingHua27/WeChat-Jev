package com.jev.relationship.domain.source

import com.jev.relationship.domain.inline.RawInlineNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleChatConversationParserTest {
    @Test
    fun `revoke notices never become incoming messages or context`() {
        val result = VisibleChatConversationParser.parse("com.tencent.mm", 1080, 1920, listOf(
            node("联系人", "android.widget.TextView", 400, 140, 600, 210),
            node("真实消息", "android.widget.TextView", 80, 400, 400, 500),
            node("你撤回了一条消息", "android.widget.TextView", 300, 600, 700, 650),
            node("\"联系人\" 撤回了一条消息", "android.widget.TextView", 300, 800, 700, 850),
            node(null, "android.widget.EditText", 100, 1740, 980, 1840),
        ))!!
        assertEquals(listOf("真实消息"), result.messages.map { it.text })
    }

    @Test
    fun `known chat composer remains valid above an open keyboard`() {
        val result = VisibleChatConversationParser.parse("com.tencent.mm", 1080, 1920, listOf(
            node("联系人", "android.widget.TextView", 400, 140, 600, 210),
            node("对方消息", "android.widget.TextView", 80, 400, 400, 500, "c3u"),
            node(null, "android.widget.EditText", 100, 1000, 980, 1100, "c3t"),
        ))
        assertEquals("对方消息", result?.anchorText)
    }
    @Test
    fun `long outgoing bubble crossing screen center is classified by its avatar`() {
        val result = VisibleChatConversationParser.parse("com.tencent.mm", 1080, 1920, listOf(
            node("联系人", "android.widget.TextView", 400, 140, 600, 210),
            node("对方消息", "android.widget.TextView", 80, 400, 400, 500, "c3u"),
            node(null, "android.widget.ImageView", 10, 400, 70, 470, "c34"),
            node("自己发出的长消息，横跨屏幕中线", "android.widget.TextView", 80, 600, 990, 740, "c3u"),
            node(null, "android.widget.ImageView", 1000, 600, 1070, 670, "c34"),
            node(null, "android.widget.EditText", 100, 1740, 980, 1840),
        ))!!
        assertEquals(listOf(false, true), result.messages.map { it.isOutgoing })
        assertEquals("对方消息", result.anchorText)
    }
    @Test
    fun `extracts visible messages and last message direction`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("文件传输助手", "android.widget.TextView", 420, 140, 660, 210),
                node("你好", "android.widget.TextView", 80, 420, 300, 500),
                node("收到", "android.widget.TextView", 760, 620, 1000, 700),
                node("输入消息", "android.widget.EditText", 100, 1740, 980, 1840),
                node("发送", "android.widget.Button", 990, 1740, 1080, 1840),
            ),
        )

        requireNotNull(result)
        assertEquals("文件传输助手", result.conversationKey)
        assertEquals("你好\n收到", result.conversationText)
        assertEquals("你好", result.anchorText)
        assertTrue(!result.anchorIsOutgoing)
        assertEquals(
            listOf("你好", "收到"),
            result.messages.map { it.text },
        )
        assertEquals(listOf(false, true), result.messages.map { it.isOutgoing })
    }

    @Test
    fun `does not produce an analyzable conversation when every message is outgoing`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("文件传输助手", "android.widget.TextView", 420, 140, 660, 210),
                node("我发出的内容", "android.widget.TextView", 760, 420, 1000, 500),
                node(null, "android.widget.EditText", 100, 1740, 980, 1840),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `returns null when the window has no visible message bubble`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("联系人", "android.widget.TextView", 420, 140, 660, 210),
                node("输入消息", "android.widget.EditText", 100, 1740, 980, 1840),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `does not analyze the WeChat conversation list without a chat composer`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("联系人一", "android.widget.TextView", 180, 420, 760, 500),
                node("联系人二", "android.widget.TextView", 180, 620, 760, 700),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `uses an anonymous chat key when the title is not exposed`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("你好", "android.widget.TextView", 80, 420, 300, 500),
                node("18:52", "android.widget.TextView", 500, 300, 580, 340),
                node("输入消息", "android.widget.EditText", 100, 1575, 950, 1713),
            ),
        )

        requireNotNull(result)
        assertEquals("wechat-chat", result.conversationKey)
        assertEquals("你好", result.conversationText)
    }

    @Test
    fun `when WeChat exposes text bubble ids, ignores payment and other text components`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("联系人", "android.widget.TextView", 420, 140, 660, 210),
                node("普通消息", "android.widget.TextView", 80, 420, 300, 500, "com.tencent.mm:id/c3u"),
                node("￥200.00", "android.widget.TextView", 80, 540, 300, 620, "com.tencent.mm:id/abq"),
                node("输入消息", "android.widget.EditText", 100, 1740, 980, 1840),
            ),
        )

        requireNotNull(result)
        assertEquals(listOf("普通消息"), result.messages.map { it.text })
    }

    @Test
    fun `accepts a chat page when the empty composer has no text`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("联系人", "android.widget.TextView", 420, 140, 660, 210),
                node("普通消息", "android.widget.TextView", 80, 420, 300, 500),
                node(null, "android.widget.EditText", 100, 1740, 980, 1840),
            ),
        )

        requireNotNull(result)
        assertEquals(listOf("普通消息"), result.messages.map { it.text })
    }

    @Test
    fun `ignores payment controls even when ordinary text ids are unavailable`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            nodes = listOf(
                node("联系人", "android.widget.TextView", 420, 140, 660, 210),
                node("普通消息", "android.widget.TextView", 80, 420, 300, 500),
                node("￥200.00", "android.widget.TextView", 80, 540, 300, 620, "com.tencent.mm:id/abq"),
                node(null, "android.widget.EditText", 100, 1740, 980, 1840),
            ),
        )

        requireNotNull(result)
        assertEquals(listOf("普通消息"), result.messages.map { it.text })
    }

    @Test
    fun `ignores voice bubbles even though WeChat exposes their duration as text`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1264,
            screenHeight = 2780,
            nodes = listOf(
                node("联系人", "android.widget.TextView", 420, 140, 660, 210),
                node("普通消息", "android.widget.TextView", 80, 420, 300, 500, "com.tencent.mm:id/c3u"),
                node("20\"", "android.widget.TextView", 183, 1965, 272, 2074, "com.tencent.mm:id/c3u"),
                node(null, "android.widget.RelativeLayout", 183, 1965, 813, 2508, "com.tencent.mm:id/c5m"),
                node("倍速播放", "android.widget.TextView", 838, 2205, 1040, 2268, "com.tencent.mm:id/c5l"),
                node(null, "android.widget.EditText", 151, 2566, 987, 2704, "com.tencent.mm:id/c3t"),
            ),
        )

        requireNotNull(result)
        assertEquals(listOf("普通消息"), result.messages.map { it.text })
    }

    @Test
    fun `keeps ordinary text when an unmarked voice duration has the bubble id`() {
        val result = VisibleChatConversationParser.parse(
            packageName = "com.tencent.mm",
            screenWidth = 1264,
            screenHeight = 2780,
            nodes = listOf(
                node("联系人", "android.widget.TextView", 420, 140, 660, 210),
                node("普通文本消息", "android.widget.TextView", 80, 420, 360, 500),
                node("20\"", "android.widget.TextView", 183, 620, 272, 729, "com.tencent.mm:id/c3u"),
                node(null, "android.widget.EditText", 151, 2566, 987, 2704, "com.tencent.mm:id/c3t"),
            ),
        )

        requireNotNull(result)
        assertEquals(listOf("普通文本消息"), result.messages.map { it.text })
    }

    private fun node(
        text: String?,
        className: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        viewIdResourceName: String? = null,
    ) = RawInlineNodeSnapshot(
        className = className,
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        visibleToUser = true,
        viewIdResourceName = viewIdResourceName,
    )
}
