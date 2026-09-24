package com.jev.relationship.xposed.ui

import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcIntentProbability

@RunWith(RobolectricTestRunner::class)
class EmbeddedChatCardHostTest {
    @Test fun `fast cached row has its final height at first measure without a second layout jump`() {
        val root = FrameLayout(context)
        val list = ChatListMarker(context)
        root.addView(list)
        val row = android.widget.RelativeLayout(context)
        val message = TextView(context).apply { text = "分析目标" }
        row.addView(message, android.widget.RelativeLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT))
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> if (row.parent != null) WechatMessageAnchor(message, false, 42L) else null },
            readLocalMessageId = { if (it === message) 42L else null })
        host.setFastCacheDisplay(true)
        host.attach(root)
        host.onAnalysisResult(result().copy(messageId = "wechat-8.0.72-42",
            textHash = WechatMessageAnchorResolver.hash("分析目标")))
        list.addView(row)
        host.prepareRowForMeasure(row)
        val width = android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY)
        val height = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        row.measure(width, height)
        val firstHeight = row.measuredHeight
        host.renderNow()
        row.measure(width, height)
        assertEquals("cached interpretation must not grow the row after its first measurement", firstHeight, row.measuredHeight)
        assertTrue(host.cardVisible)
        repeat(5) {
            host.prepareRowForMeasure(row)
            row.measure(width, height)
            assertEquals("repeated measurement must not accumulate extra height", firstHeight, row.measuredHeight)
        }
        assertEquals(1, host.createdCardCount)
        host.destroy()
    }

    @Test fun `reenter restores fast cached cards when chat list is created after attach`() {
        val root = FrameLayout(context)
        val message = TextView(context).apply { text = "分析目标" }
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> WechatMessageAnchor(message, false, 42L) }, readLocalMessageId = { 42L })
        host.setFastCacheDisplay(true)
        host.attach(root)
        val cached = result().copy(messageId = "wechat-8.0.72-42", textHash = WechatMessageAnchorResolver.hash("分析目标"))
        host.onAnalysisResult(cached)
        host.onPause()
        host.onResume()
        val list = ChatListMarker(context)
        root.addView(list)
        list.addView(message)
        root.viewTreeObserver.dispatchOnGlobalLayout()
        root.viewTreeObserver.dispatchOnPreDraw()
        host.onAnalysisResult(cached)
        shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS)
        assertTrue("same cached data must recover after the native list becomes ready", host.cardVisible)
        assertTrue(message.parent is LinearLayout)
        host.destroy()
    }
    @Test fun `fast mode retains old cached results without creating offscreen cards`() {
        val (root, _, message) = attachedRoot()
        var visibleId: Long? = null
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, value -> if (value.messageId == "wechat-8.0.72-$visibleId")
                WechatMessageAnchor(message, false, visibleId) else null },
            readLocalMessageId = { visibleId }, maxCachedResults = 3)
        host.attach(root)
        host.setFastCacheDisplay(true)
        host.onAnalysisResults((1..20).map { result().copy(messageId = "wechat-8.0.72-$it",
            textHash = WechatMessageAnchorResolver.hash("分析目标")) })
        assertEquals(20, host.cachedResultCount)
        assertEquals(0, host.retainedCardCount)
        visibleId = 1L
        host.renderNow()
        assertEquals(1, host.retainedCardCount)
        assertTrue(host.cardVisible)
        host.setFastCacheDisplay(false)
        assertTrue(host.cachedResultCount <= 3)
        assertTrue(host.cardVisible)
        host.destroy()
    }
    @Test fun `offscreen results and views are bounded and evicted results can be restored`() {
        val (root, _, message) = attachedRoot()
        var target: TextView? = null
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, value -> target?.takeIf { value.messageId == "wechat-8.0.72-1" }
                ?.let { WechatMessageAnchor(it, false, 1L) } },
            maxCachedResults = 3,
        )
        host.attach(root)
        val old = result().copy(messageId = "wechat-8.0.72-1", textHash = WechatMessageAnchorResolver.hash("分析目标"))
        host.onAnalysisResults((1..8).map { old.copy(messageId = "wechat-8.0.72-$it") })
        assertEquals(3, host.cachedResultCount)
        assertTrue(host.retainedCardCount <= 3)
        target = message
        host.onAnalysisResult(old) // A normal database cache replay, not a model call.
        assertTrue(host.cardVisible)
        assertEquals(3, host.cachedResultCount)
        host.destroy()
        assertEquals(0, host.retainedCardCount)
    }

    @Test fun `memory eviction keeps attached native rows and their cards intact`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val (root, _, message) = attachedRoot()
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        val old = result().copy(messageId = "wechat-8.0.72-42", textHash = WechatMessageAnchorResolver.hash("分析目标"))
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, value -> if (value.messageId == old.messageId)
                WechatMessageAnchor(message, false, 42L) else null },
            readLocalMessageId = { 42L }, maxCachedResults = 1,
        )
        try {
            host.attach(root)
            host.onAnalysisResult(old)
            val row = message.parent
            host.onAnalysisResults((50..60).map { old.copy(messageId = "wechat-8.0.72-$it") })
            assertTrue(host.cardVisible)
            assertTrue("eviction cannot move or remove the native message", message.parent === row)
            assertEquals(1, host.cachedResultCount)
            assertEquals(1, host.retainedCardCount)
        } finally { host.destroy(); activity.finish() }
    }

    @Test fun `batch resolves all message anchors once and successful mounts stop timed retries`() {
        val (root, container, first) = attachedRoot()
        val second = TextView(context).apply { text = "第二条" }
        container.addView(second)
        var scans = 0
        var identityReads = 0
        val values = listOf(
            result().copy(messageId = "wechat-8.0.72-42", textHash = WechatMessageAnchorResolver.hash("分析目标")),
            result().copy(messageId = "wechat-8.0.72-43", textHash = WechatMessageAnchorResolver.hash("第二条")),
        )
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> error("use batch resolver") },
            resolveTargets = { _, results ->
                scans++
                results.associate { it.messageId to if (it.messageId.endsWith("42"))
                    WechatMessageAnchor(first, false, 42L) else WechatMessageAnchor(second, false, 43L) }
            },
            readLocalMessageId = { identityReads++; if (it === first) 42L else 43L },
        )
        host.attach(root)
        host.onAnalysisResults(values)
        assertEquals("one anchor traversal per cache batch", 1, scans)
        assertEquals(2, host.createdCardCount)
        assertTrue(host.cardVisible)
        val readsAfterMount = identityReads
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals("successful mounting should cancel scheduled retry work", readsAfterMount, identityReads)
        host.destroy()
    }

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `cache replay repairs a native message moved out of its old card wrapper`() {
        val (root, container, message) = attachedRoot()
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> WechatMessageAnchor(message, false, 42L) },
            readLocalMessageId = { 42L },
        )
        val cached = result().copy(messageId = "wechat-8.0.72-42", textHash = WechatMessageAnchorResolver.hash("分析目标"))
        host.attach(root)
        host.onAnalysisResult(cached)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        val wrapper = message.parent as ViewGroup
        val card = wrapper.getChildAt(1)
        wrapper.removeView(message)
        container.addView(message)
        container.removeView(wrapper)
        host.onAnalysisResult(cached)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertTrue("the cached card must rejoin the current native row", card.parent === message.parent)
        assertTrue(message.parent !== container)
        assertEquals(1, host.createdCardCount)
        host.destroy()
    }

    @Test
    fun `detached stable message keeps its cached card until the row identity changes`() {
        val (root, container, message) = attachedRoot()
        var target: TextView? = message
        var localId = 42L
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> target?.let { WechatMessageAnchor(it, false, localId) } },
            readLocalMessageId = { localId },
        )
        host.attach(root)
        host.onAnalysisResult(result().copy(messageId = "wechat-8.0.72-42",
            textHash = WechatMessageAnchorResolver.hash("分析目标")))
        val wrapper = message.parent as ViewGroup
        val card = wrapper.getChildAt(1)
        container.removeView(wrapper)
        target = null
        host.renderNow()

        assertTrue("scrolling offscreen must preserve the card and row geometry", card.parent === wrapper)
        assertTrue(host.cardVisible)
        localId = 43L
        host.renderNow()
        assertFalse("a recycled row must never retain another message's interpretation", host.cardVisible)
        assertTrue("cleanup must preserve the original message", message.parent === wrapper)
        host.destroy()
    }

    @Test
    fun `cached card is rebound before the next draw without waiting for scrolling to stop`() {
        val (root, container, original) = attachedRoot()
        val rebound = TextView(context).apply { text = "分析目标" }
        var target = original
        var resolutions = 0
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> resolutions++; WechatMessageAnchor(target, false, 42L) },
            readLocalMessageId = { 42L },
        )
        host.attach(root)
        host.onAnalysisResult(result().copy(messageId = "wechat-8.0.72-42",
            textHash = WechatMessageAnchorResolver.hash("分析目标")))
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        container.removeView(original.parent as ViewGroup)
        container.addView(rebound)
        target = rebound
        repeat(5) {
            root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply {
                isAccessible = true
            }.invoke(root.viewTreeObserver)
        }
        val before = resolutions

        assertTrue("defer drawing until the restored row is measured", root.viewTreeObserver.dispatchOnPreDraw())
        assertTrue("restore in this frame, without an 80 ms delay", rebound.parent is LinearLayout)
        assertEquals(before + 1, resolutions)
        assertFalse("the next frame must not be blocked again", root.viewTreeObserver.dispatchOnPreDraw())
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals("pre-draw consumes the pending fallback scan", before + 1, resolutions)
        assertEquals(1, host.createdCardCount)
        host.destroy()
    }

    @Test
    fun `identical cache replay does not trigger scans or retry bursts`() {
        val (root, _, message) = attachedRoot()
        var resolutions = 0
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> resolutions++; WechatMessageAnchor(message, false) },
        )
        host.attach(root)
        val cached = result()
        host.onAnalysisResult(cached)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        val before = resolutions
        repeat(20) { host.setAnalysisEnabled(true); host.onAnalysisResult(cached) }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals("unchanged cache replay must not rescan or schedule retries", before, resolutions)
        assertTrue(host.cardVisible)
        host.destroy()
    }

    @Test
    fun `result before attach is retained and attach creates one card`() {
        val (root, container, message) = attachedRoot()
        val host = host(message)

        host.onAnalysisResult(result())
        assertEquals(2, container.childCount)

        host.attach(root)

        assertEquals(2, container.childCount)
        assertEquals(1, host.createdCardCount)
    }

    @Test
    fun `repeated render reuses the same card view`() {
        val (root, container, message) = attachedRoot()
        val host = host(message)
        host.onAnalysisResult(result())
        host.attach(root)
        host.renderNow()
        host.renderNow()

        assertEquals(1, host.createdCardCount)
        assertEquals(2, host.containerChildCount())
    }

    @Test
    fun `stable attached message skips full anchor resolution during layout`() {
        val (root, _, message) = attachedRoot()
        var resolveCount = 0
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ ->
                resolveCount += 1
                WechatMessageAnchor(message, outgoing = false, localMessageId = 42L)
            },
            readLocalMessageId = { 42L },
        )
        host.attach(root)
        host.onAnalysisResult(
            result().copy(
                messageId = "wechat-8.0.72-42",
                textHash = WechatMessageAnchorResolver.hash("分析目标"),
            ),
        )
        assertEquals(1, resolveCount)

        repeat(5) { root.viewTreeObserver.dispatchOnGlobalLayout() }
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertEquals("an unchanged mounted card must not rescan the entire chat tree", 1, resolveCount)
        host.destroy()
    }

    @Test
    fun `viewport callbacks coalesce into one chat resolution after scrolling`() {
        val (root, _, message) = attachedRoot()
        var resolveCount = 0
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ ->
                resolveCount += 1
                WechatMessageAnchor(message, outgoing = false)
            },
        )
        host.attach(root)
        host.onAnalysisResult(result())
        shadowOf(Looper.getMainLooper()).idleFor(1_200, TimeUnit.MILLISECONDS)
        val settledCount = resolveCount

        repeat(5) {
            root.viewTreeObserver.dispatchOnGlobalLayout()
            root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply {
                isAccessible = true
            }.invoke(root.viewTreeObserver)
        }
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertEquals("a burst of viewport changes should resolve anchors once", settledCount + 1, resolveCount)
        host.destroy()
    }

    @Test
    fun `attached host resolves all restored results in one hierarchy pass`() {
        val (root, _, message) = attachedRoot()
        var batchCalls = 0
        var singleCalls = 0
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ ->
                singleCalls += 1
                WechatMessageAnchor(message, outgoing = false)
            },
            resolveTargets = { _, results ->
                batchCalls += 1
                assertEquals(3, results.size)
                emptyMap()
            },
        )
        repeat(3) { index -> host.onAnalysisResult(result().copy(messageId = "message-$index")) }

        host.attach(root)

        assertEquals(1, batchCalls)
        assertEquals(0, singleCalls)
        host.destroy()
    }

    @Test
    fun `card keeps the container compatible layout params`() {
        val (root, container, message) = attachedRoot()
        val host = host(message)

        host.onAnalysisResult(result())
        host.attach(root)

        val card = (container.getChildAt(1) as ViewGroup).getChildAt(1)
        assertTrue(card.layoutParams is ViewGroup.MarginLayoutParams)
    }

    @Test
    fun `pause and clear hide card while destroy removes it`() {
        val (root, container, message) = attachedRoot()
        val host = host(message)
        host.onAnalysisResult(result())
        host.attach(root)

        host.onPause()
        assertFalse(host.cardVisible)

        host.clear()
        assertFalse(host.cardVisible)

        host.destroy()
        assertEquals(2, container.childCount)
        assertTrue(host.destroyed)
    }

    @Test
    fun `attach retries after chat list is populated`() {
        val root = FrameLayout(context)
        val container = FrameLayout(context)
        root.addView(container)
        container.addView(ChatListMarker(context))
        var message: TextView? = null
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> message?.let { WechatMessageAnchor(it, outgoing = false) } },
        )

        host.onAnalysisResult(result())
        host.attach(root)
        assertFalse(host.cardVisible)

        message = TextView(context).apply {
            text = "分析目标"
            layout(20, 100, 180, 150)
        }
        container.addView(message)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        assertTrue(host.cardVisible)
    }

    @Test
    fun `late result retries after the initial attach retries finish`() {
        val root = FrameLayout(context)
        val container = FrameLayout(context)
        root.addView(container)
        container.addView(ChatListMarker(context))
        var message: TextView? = null
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> message?.let { WechatMessageAnchor(it, outgoing = false) } },
        )

        host.attach(root)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        host.onAnalysisResult(result())
        message = TextView(context).apply {
            text = "分析目标"
            layout(20, 100, 180, 150)
        }
        container.addView(message)

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        assertTrue(host.cardVisible)
    }

    @Test
    fun `rebinds cached card to the same stable message after row recycling`() {
        val root = FrameLayout(context)
        val container = FrameLayout(context)
        val original = TextView(context).apply { text = "群消息" }
        val rebound = TextView(context).apply { text = "群消息" }
        root.addView(container)
        container.addView(ChatListMarker(context))
        container.addView(original)
        container.addView(rebound)
        var target = original
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> WechatMessageAnchor(target, false, localMessageId = 42L) },
        )
        host.attach(root)
        host.onAnalysisResult(result().copy(messageId = "wechat-8.0.72-42", textHash = WechatMessageAnchorResolver.hash("群消息")))
        assertTrue(host.cardVisible)

        container.removeView(original.parent as ViewGroup)
        target = rebound
        root.viewTreeObserver.dispatchOnGlobalLayout()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertTrue(host.cardVisible)
        assertTrue(rebound.parent is LinearLayout)
        assertEquals(1, host.createdCardCount)
    }

    private fun host(message: TextView) = EmbeddedChatCardHost(
        context = context,
        locator = WechatChatViewLocator(ChatListMarker::class.java.name),
        resolveTarget = { _, _ -> WechatMessageAnchor(message, outgoing = false) },
    )

    @Test
    fun `scroll event restores cached analysis after the stable row is rebound`() {
        val (root, container, original) = attachedRoot()
        val rebound = TextView(context).apply { text = "分析目标" }
        container.addView(rebound)
        var target: TextView? = original
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> target?.let { WechatMessageAnchor(it, false, 42L) } },
        )
        host.attach(root)
        host.onAnalysisResult(result().copy(messageId = "wechat-8.0.72-42"))
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        target = null
        root.viewTreeObserver.dispatchOnGlobalLayout()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertFalse(host.cardVisible)

        target = rebound
        root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply {
            isAccessible = true
        }.invoke(root.viewTreeObserver)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertTrue("scroll must restore an already analyzed row without another result", host.cardVisible)
        assertTrue(rebound.parent is LinearLayout)
        assertEquals(1, host.createdCardCount)
        host.destroy()
    }

    @Test
    fun `layout after row recycling removes the old message panel`() {
        val (root, _, message) = attachedRoot()
        var matches = true
        val host = EmbeddedChatCardHost(context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> if (matches) WechatMessageAnchor(message, false) else null },
        )
        host.attach(root)
        host.onAnalysisResult(result())
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(host.cardVisible)
        matches = false
        root.viewTreeObserver.dispatchOnGlobalLayout()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertFalse(host.cardVisible)
    }

    @Test
    fun `outgoing result cannot create an embedded card`() {
        val (root, _, message) = attachedRoot()
        val host = host(message)
        host.attach(root)
        host.onAnalysisResult(result().copy(isOutgoing = true))
        assertFalse(host.cardVisible)
        assertEquals(0, host.createdCardCount)
    }

    private fun attachedRoot(): Triple<FrameLayout, FrameLayout, TextView> {
        val root = FrameLayout(context)
        val container = FrameLayout(context)
        val message = TextView(context).apply { text = "分析目标" }
        root.addView(container)
        container.addView(ChatListMarker(context))
        container.addView(message)
        return Triple(root, container, message)
    }

    private fun result() = IpcAnalysisResult(
        messageId = "message-1",
        conversationHash = "conversation-hash",
        textHash = "text-hash",
        isOutgoing = false,
        emotion = "平静",
        intents = listOf(IpcIntentProbability("沟通", 0.8)),
        riskLevel = 3,
        suggestion = "保持清晰。",
    )

    private class ChatListMarker(context: Context) : FrameLayout(context)
}
