package com.jev.relationship.xposed.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EmbeddedChatMessageInserterTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `reattaches card when native message was reparented but old wrapper remains attached`() {
        val parent = FrameLayout(context)
        val message = TextView(context)
        val card = TextView(context)
        parent.addView(message)
        val inserter = EmbeddedChatMessageInserter()
        inserter.insertBelow(message, card)
        val oldWrapper = message.parent as android.view.ViewGroup
        oldWrapper.removeView(message)
        parent.addView(message)
        inserter.insertBelow(message, card)
        assertSame(message.parent, card.parent)
        org.junit.Assert.assertNotSame(parent, message.parent)
        inserter.remove()
        assertSame(parent, message.parent)
        assertEquals(1, parent.childCount)
    }

    @Test
    fun `detached row keeps its message and cannot retain ownership of an inserter reused elsewhere`() {
        val firstParent = FrameLayout(context)
        val secondParent = FrameLayout(context)
        val first = TextView(context)
        val second = TextView(context)
        firstParent.addView(first)
        secondParent.addView(second)
        val owner = EmbeddedChatMessageInserter()
        owner.insertBelow(first, TextView(context))
        val detached = firstParent.getChildAt(0)
        firstParent.removeView(detached)
        owner.remove()
        assertSame(detached, first.parent)
        owner.insertBelow(second, TextView(context))
        firstParent.addView(detached)
        val fresh = EmbeddedChatMessageInserter()
        org.junit.Assert.assertTrue(fresh.insertBelow(first, TextView(context)))
        assertSame(second, owner.boundMessage)
        fresh.remove()
        owner.remove()
        assertSame(firstParent, first.parent)
        assertSame(secondParent, second.parent)
    }

    @Test
    fun `old card cleanup must keep a message rebound inside a newer card wrapper visible`() {
        val parent = FrameLayout(context)
        val message = TextView(context).apply { text = "原消息不能消失" }
        parent.addView(message)
        val old = EmbeddedChatMessageInserter()
        val current = EmbeddedChatMessageInserter()
        old.insertBelow(message, TextView(context))
        current.insertBelow(message, TextView(context))
        old.remove()
        var ancestor: android.view.View? = message
        while (ancestor != null && ancestor !== parent) ancestor = ancestor.parent as? android.view.View
        assertSame("cleanup of an obsolete card must not detach the current message", parent, ancestor)
        current.remove()
        assertSame(parent, message.parent)
        assertEquals(1, parent.childCount)
    }

    @Test
    fun `cleanup preserves a message after its wrapper moves to another row parent`() {
        val original = FrameLayout(context)
        val destination = FrameLayout(context)
        val message = TextView(context)
        original.addView(message)
        val inserter = EmbeddedChatMessageInserter()
        inserter.insertBelow(message, TextView(context))
        val wrapper = original.getChildAt(0)
        original.removeView(wrapper)
        destination.addView(wrapper)
        inserter.remove()
        assertSame(destination, message.parent)
        assertEquals(1, destination.childCount)
    }

    @Test
    fun `inserts analysis directly below message inside the same row`() {
        val parent = FrameLayout(context)
        val message = TextView(context).apply { text = "对方消息" }
        val analysis = TextView(context).apply { text = "Jev" }
        parent.addView(message)
        val inserter = EmbeddedChatMessageInserter()

        inserter.insertBelow(message, analysis)

        val row = parent.getChildAt(0) as LinearLayout
        assertSame(parent, row.parent)
        assertSame(message, row.getChildAt(0))
        assertSame(analysis, row.getChildAt(1))
        assertEquals(2, row.childCount)
    }

    @Test
    fun `removes analysis and restores the original message hierarchy`() {
        val parent = FrameLayout(context)
        val message = TextView(context).apply { text = "对方消息" }
        val analysis = TextView(context).apply { text = "Jev" }
        parent.addView(message)
        val inserter = EmbeddedChatMessageInserter()
        inserter.insertBelow(message, analysis)

        inserter.remove()

        assertSame(message, parent.getChildAt(0))
        assertEquals(1, parent.childCount)
        assertEquals(null, analysis.parent)
    }

    @Test
    fun `does not remove a message row from a recycler parent`() {
        val recycler = FakeRecyclerView(context)
        val messageRow = FrameLayout(context)
        val message = TextView(context).apply { text = "虚拟消息行" }
        val analysis = TextView(context).apply { text = "Jev" }
        messageRow.addView(message)
        recycler.addView(messageRow)
        messageRow.layout(0, 0, 300, 100)

        val inserted = EmbeddedChatMessageInserter().insertBelow(messageRow, analysis)

        assertEquals(true, inserted)
        assertSame(messageRow, recycler.getChildAt(0))
        assertSame(analysis, messageRow.getChildAt(messageRow.childCount - 1))
    }

    @Test
    fun `waits for a recycler message row to be measured before inserting`() {
        val recycler = FakeRecyclerView(context)
        val messageRow = FrameLayout(context)
        val analysis = TextView(context).apply { text = "Jev" }
        recycler.addView(messageRow)

        val inserted = EmbeddedChatMessageInserter().insertBelow(messageRow, analysis)

        assertEquals(false, inserted)
        assertEquals(0, messageRow.childCount)
    }

    private class FakeRecyclerView(context: Context) : FrameLayout(context)

    @Test
    fun `cleanup does not steal a message reparented by WeChat`() {
        val parent = FrameLayout(context)
        val replacementParent = FrameLayout(context)
        val message = TextView(context)
        parent.addView(message)
        val inserter = EmbeddedChatMessageInserter()
        inserter.insertBelow(message, TextView(context))
        (message.parent as android.view.ViewGroup).removeView(message)
        replacementParent.addView(message)
        inserter.remove()
        assertSame(replacementParent, message.parent)
        assertEquals(0, parent.childCount)
    }

    @Test
    fun `keeps original bubble margins on wrapper`() {
        val parent = FrameLayout(context)
        val message = TextView(context)
        val params = FrameLayout.LayoutParams(180, 50).apply { leftMargin = 40; topMargin = 8 }
        parent.addView(message, params)
        val inserter = EmbeddedChatMessageInserter()
        inserter.insertBelow(message, TextView(context))
        val wrapperParams = parent.getChildAt(0).layoutParams as FrameLayout.LayoutParams
        assertEquals(40, wrapperParams.leftMargin)
        assertEquals(8, wrapperParams.topMargin)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, wrapperParams.height)
        inserter.remove()
        assertSame(params, message.layoutParams)
    }
}
