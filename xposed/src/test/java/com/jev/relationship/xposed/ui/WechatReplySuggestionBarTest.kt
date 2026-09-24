package com.jev.relationship.xposed.ui

import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.relationship.ipc.IpcReplySuggestion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WechatReplySuggestionBarTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `manual hiding survives new results and toggles without losing cached reply`() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "cal" }
        val footer = FrameLayout(context)
        footer.addView(EditText(context).apply { tag = "c3t" })
        column.addView(footer)
        val bar = WechatReplySuggestionBar { it.tag as? String ?: "" }
        bar.setConversation("chat")
        bar.attach(column)
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-1", "旧建议"))
        val row = column.getChildAt(0) as LinearLayout
        bar.setUserHidden(true)
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-2", "新建议"))
        bar.setEnabled(false)
        bar.setEnabled(true)
        assertEquals(View.GONE, row.visibility)
        bar.setUserHidden(false)
        assertEquals(View.VISIBLE, row.visibility)
        assertTrue((row.getChildAt(0) as TextView).text.contains("新建议"))
        assertSame(footer, column.getChildAt(1))
    }

    @Test fun `reply bar inserts above footer and fills draft without sending or reparenting native views`() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "cal" }
        val messages = FrameLayout(context)
        val footer = FrameLayout(context)
        val input = EditText(context).apply { tag = "c3t"; setText("我的草稿") }
        var sends = 0
        input.setOnEditorActionListener { _, _, _ -> sends++; true }
        footer.addView(input)
        column.addView(messages)
        column.addView(footer)
        val bar = WechatReplySuggestionBar { it.tag as? String ?: "" }
        bar.setConversation("chat")
        bar.attach(column)
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-20", "好的，明天见"))
        val row = column.getChildAt(1) as LinearLayout
        val button = row.getChildAt(1) as TextView
        assertEquals("回复", button.text.toString())
        assertSame(footer, column.getChildAt(2))
        assertSame(footer, input.parent)
        button.performClick()
        assertEquals("我的草稿\n好的，明天见", input.text.toString())
        button.performClick()
        assertEquals("我的草稿\n好的，明天见", input.text.toString())
        assertEquals(0, sends)
        bar.detach()
        assertEquals(2, column.childCount)
        assertSame(footer, column.getChildAt(1))
        assertSame(footer, input.parent)
    }

    @Test fun `new incoming invalidates reply and older or other conversation results cannot overwrite it`() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "cal" }
        val footer = FrameLayout(context)
        footer.addView(EditText(context).apply { tag = "c3t" })
        column.addView(footer)
        val bar = WechatReplySuggestionBar { it.tag as? String ?: "" }
        bar.setConversation("chat")
        bar.attach(column)
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-20", "旧建议"))
        val row = column.getChildAt(0) as LinearLayout
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-21", ""))
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-20", "过期结果"))
        assertEquals(View.GONE, row.visibility)
        bar.update(IpcReplySuggestion("other", "wechat-8.0.72-22", "别人的回复"))
        assertEquals(View.GONE, row.visibility)
        bar.update(IpcReplySuggestion("chat", "wechat-8.0.72-21", "新建议"))
        assertEquals(View.VISIBLE, row.visibility)
        bar.setEnabled(false)
        assertEquals(View.GONE, row.visibility)
        bar.setEnabled(true)
        assertEquals(View.VISIBLE, row.visibility)
        assertTrue((row.getChildAt(0) as TextView).text.contains("新建议"))
        bar.setConversation("other")
        assertEquals(View.GONE, row.visibility)
    }
}
