package com.jev.relationship.xposed.ui

import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.xposed.hook.HookInstallResult
import com.jev.relationship.xposed.hook.WechatHookHandle
import com.jev.relationship.xposed.hook.WechatUiHookInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WechatChatUiHookTest {
    @Test fun `fast mode keeps active conversation beyond cap and switching restores bounds`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val fragment = AssistantChatFragment(activity)
        val hook = WechatChatUiHook(activity, installer, maxCachedResults = 3,
            classResolver = { _, _ -> AssistantChatFragment::class.java })
        hook.install()
        try {
            installer.callback("onCreateView", FrameLayout(activity), fragment)
            hook.setFastCacheDisplay(true)
            hook.onAnalysisResults((1..20).map { result().copy(messageId = "m$it",
                conversationHash = WechatMessageAnchorResolver.hash("alice")) })
            assertEquals(20, hook.cachedResultCount)
            fragment.conversationId = "bob"
            installer.callback("onCreateView", FrameLayout(activity), fragment)
            assertEquals(3, hook.cachedResultCount)
            hook.onAnalysisResults((1..20).map { result().copy(messageId = "b$it",
                conversationHash = WechatMessageAnchorResolver.hash("bob")) })
            assertEquals(23, hook.cachedResultCount)
            hook.setFastCacheDisplay(false)
            assertEquals(3, hook.cachedResultCount)
        } finally { hook.uninstall(); activity.finish() }
    }
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun `cross conversation memory retains a bounded set of recent results`() {
        val hook = WechatChatUiHook(context, RecordingInstaller(), maxCachedResults = 3)
        repeat(8) { hook.onAnalysisResult(result().copy(messageId = "m$it", conversationHash = "c$it")) }
        assertEquals(3, hook.cachedResultCount)
        hook.clear()
        assertEquals("hiding must retain memory cache", 3, hook.cachedResultCount)
        hook.uninstall()
    }

    @Test fun `header actions are installed when chrome becomes available after fragment attachment`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = RelativeLayout(activity)
        val root = FrameLayout(activity)
        var chromeReady = false
        val hook = WechatChatUiHook(activity, installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { if (chromeReady) actionBar else null },
        )
        hook.install()
        try {
            installer.callback("onCreateView", root, AssistantChatFragment(activity))
            assertEquals(0, actionBar.childCount)
            chromeReady = true
            activity.setContentView(root)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertEquals(2, actionBar.childCount)
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun headerActionsDoNotCoverCenteredChatNickname() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val density = context.resources.displayMetrics.density
        val width = (400 * density).toInt()
        val height = (56 * density).toInt()
        val actionBar = RelativeLayout(activity)
        val title = TextView(activity).apply {
            text = "昵称昵称"
            gravity = Gravity.CENTER
        }
        actionBar.addView(title, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT,
        ))
        val root = FrameLayout(activity).apply {
            addView(actionBar, FrameLayout.LayoutParams(width, height))
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, width, height)
        }
        val hook = WechatChatUiHook(
            context = activity,
            installer = installer,
            classResolver = { _, _ -> FakeChatFragment::class.java },
            actionBarResolver = { actionBar },
        )
        hook.install()
        try {
            installer.callback("onCreateView", root)
            actionBar.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            actionBar.layout(0, 0, width, height)

            val titleTextLeft = width / 2f - title.paint.measureText(title.text.toString()) / 2f
            val titleTextRight = width / 2f + title.paint.measureText(title.text.toString()) / 2f
            val buttons = (0 until actionBar.childCount)
                .map(actionBar::getChildAt)
                .filterIsInstance<TextView>()
                .filter { it.text.toString() == "Jev" || it.text.toString() == "AI分析" }

            assertEquals(2, buttons.size)
            val aiButton = buttons.single { it.text.toString() == "AI分析" }
            val jevButton = buttons.single { it.text.toString() == "Jev" }
            assertTrue("AI分析 should sit left of the nickname", aiButton.right <= titleTextLeft)
            assertTrue("Jev should sit right of the nickname", jevButton.left >= titleTextRight)
            assertEquals((48 * density).toInt(), width - jevButton.right)
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `supported fragment lifecycle attaches one embedded host and forwards results`() {
        val installer = RecordingInstaller()
        val host = EmbeddedChatCardHost(
            context = context,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { container, result ->
                WechatMessageAnchorResolver().resolve(container, result)
            },
        )
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            hostFactory = { host },
            classResolver = { _, _ -> FakeChatFragment::class.java },
        )

        assertEquals(HookInstallResult.INSTALLED, hook.install())
        val root = FrameLayout(context)
        root.layout(0, 0, 400, 800)
        root.addView(ChatListMarker(context))
        root.addView(TextView(context).apply {
            text = "hello"
            layout(20, 100, 180, 150)
        })
        val result = result()
        hook.onAnalysisResult(result)
        installer.callback("onCreateView", root)

        assertEquals(1, host.createdCardCount)
        assertTrue(host.cardVisible)

        installer.callback("onPause", null)
        assertTrue(!host.cardVisible)
        installer.callback("onResume", null)
        assertTrue(host.cardVisible)
        hook.clear() // A delayed disconnect can arrive after unlock/onResume.
        assertTrue(!host.cardVisible)
        hook.onConnectionRestored()
        assertTrue("reconnection must restore cached cards without another model result", host.cardVisible)
        installer.callback("onDestroyView", null)
        assertTrue(host.destroyed)
        hook.onAnalysisResult(result)
        assertEquals(1, host.createdCardCount)
    }

    @Test
    fun `chat relayout does not rescan visible messages but scrolling does`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        var snapshotCount = 0
        val root = FrameLayout(activity)
        val chatList = ChatListMarker(activity)
        val message = TextView(activity).apply {
            text = "hello"
        }
        chatList.addView(message)
        root.addView(chatList)
        root.layout(0, 0, 400, 800)
        chatList.layout(0, 0, 400, 800)
        message.layout(20, 100, 180, 150)
        JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
        val hook = WechatChatUiHook(
            context = activity,
            installer = installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            onVisibleChat = { snapshotCount += 1 },
            visibleParser = WechatVisibleChatSnapshotParser(
                WechatChatViewLocator(ChatListMarker::class.java.name),
            ),
        )
        hook.install()
        try {
            installer.callback("onCreateView", root, AssistantChatFragment(activity))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("entry should restore the cache without waiting for scroll debounce", 1, snapshotCount)

            root.viewTreeObserver.dispatchOnGlobalLayout()
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertEquals("card insertion relayout must not start another chat scan", 1, snapshotCount)

            root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply {
                isAccessible = true
            }.invoke(root.viewTreeObserver)
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertEquals("unchanged visible messages must not replay the cache", 1, snapshotCount)
            message.text = "a newly visible message"
            repeat(5) {
                root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply {
                    isAccessible = true
                }.invoke(root.viewTreeObserver)
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            }
            assertEquals("continuous scrolling waits until the viewport settles", 1, snapshotCount)
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertEquals(2, snapshotCount)
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test fun `entry retries late rows promptly and reconnect resumes without debounce`() {
        withSnapshotFixture { hook, installer, fragment, root, chatList, snapshots ->
            val looper = shadowOf(Looper.getMainLooper())
            installer.callback("onCreateView", root, fragment)
            installer.callback("onResume", null, fragment)
            looper.idle()
            assertTrue(snapshots.isEmpty())
            addVisibleMessage(chatList, "hello")
            repeat(2) {
                root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply {
                    isAccessible = true
                }.invoke(root.viewTreeObserver)
                looper.idleFor(50, TimeUnit.MILLISECONDS)
            }
            assertEquals("layout and scroll must not postpone entry recovery", listOf("alice"), snapshots)
            hook.clear()
            hook.onConnectionRestored()
            looper.idle()
            assertEquals("reconnection should immediately request cached visible results", 2, snapshots.size)
            looper.idleFor(2, TimeUnit.SECONDS)
            assertEquals("successful entry must stop readiness retries", 2, snapshots.size)
        }
    }

    @Test fun `paused entry cancels pending scans and next chat never publishes old conversation`() {
        withSnapshotFixture { _, installer, fragment, root, chatList, snapshots ->
            val looper = shadowOf(Looper.getMainLooper())
            installer.callback("onCreateView", root, fragment)
            installer.callback("onResume", null, fragment)
            looper.idle()
            installer.callback("onPause", null, fragment)
            addVisibleMessage(chatList, "hello")
            looper.idleFor(2, TimeUnit.SECONDS)
            assertTrue("paused readiness retries must not submit messages", snapshots.isEmpty())
            fragment.conversationId = "bob"
            JevConversationAnalysisPrefs.setEnabled(context, "bob", true)
            installer.callback("onResume", null, fragment)
            looper.idle()
            assertEquals(listOf("bob"), snapshots)
        }
    }

    private fun withSnapshotFixture(block: (WechatChatUiHook, RecordingInstaller, AssistantChatFragment,
        FrameLayout, ChatListMarker, MutableList<String>) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val root = FrameLayout(activity)
        val list = ChatListMarker(activity)
        root.addView(list)
        root.layout(0, 0, 400, 800)
        list.layout(0, 0, 400, 800)
        val snapshots = mutableListOf<String>()
        val fragment = AssistantChatFragment(activity).apply { view = root }
        JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
        val hook = WechatChatUiHook(activity, installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            visibleParser = WechatVisibleChatSnapshotParser(WechatChatViewLocator(ChatListMarker::class.java.name)),
            onVisibleChat = { snapshots += it.conversationId },
        )
        hook.install()
        try { block(hook, installer, fragment, root, list, snapshots) }
        finally { hook.uninstall(); activity.finish() }
    }

    private fun addVisibleMessage(list: ChatListMarker, text: String) {
        list.addView(TextView(list.context).apply {
            this.text = text
            layout(20, 100, 180, 150)
        })
    }

    @Test
    fun `missing target methods does not leave partial hooks installed`() {
        val installer = RecordingInstaller()
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            classResolver = { _, _ -> PartialChatFragment::class.java },
        )

        assertEquals(HookInstallResult.TARGET_METHOD_UNAVAILABLE, hook.install())
        assertEquals(0, installer.installedCount)
    }

    @Test
    fun `finds lifecycle methods inherited from fragment base class`() {
        val installer = RecordingInstaller()
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            classResolver = { _, _ -> InheritedChatFragment::class.java },
        )

        assertEquals(HookInstallResult.INSTALLED, hook.install())
        assertEquals(5, installer.installedCount)
    }

    @Test
    fun `analysis click resolves the current chat after a fragment is reused`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = android.widget.RelativeLayout(activity)
        var request: com.jev.relationship.ipc.ChatAssistantRequest? = null
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
            onChatAssistantRequest = { value, _ -> request = value; true },
        )
        hook.install()
        val root = FrameLayout(activity).apply { addView(actionBar) }
        val fragment = AssistantChatFragment(activity)
        try {
            installer.callback("onCreateView", root, fragment)
            fragment.conversationId = "bob"
            (0 until actionBar.childCount).map(actionBar::getChildAt)
                .filterIsInstance<TextView>().single { it.text.toString() == "AI分析" }.performClick()
            assertEquals("bob", request?.conversationId)
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `late destruction of the previous fragment does not remove the new chat buttons`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = android.widget.RelativeLayout(activity)
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
        )
        hook.install()
        val oldFragment = AssistantChatFragment(activity)
        val newFragment = AssistantChatFragment(activity).apply { conversationId = "bob" }
        try {
            installer.callback("onCreateView", FrameLayout(activity), oldFragment)
            installer.callback("onCreateView", FrameLayout(activity), newFragment)
            installer.callback("onDestroyView", null, oldFragment)
            assertTrue((0 until actionBar.childCount).map(actionBar::getChildAt)
                .filterIsInstance<TextView>().any { it.text.toString() == "AI分析" })
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `analysis title comes from action bar instead of message timestamps or Jev buttons`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = android.widget.RelativeLayout(activity)
        var request: com.jev.relationship.ipc.ChatAssistantRequest? = null
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
            onChatAssistantRequest = { value, _ -> request = value; true },
        )
        val root = FrameLayout(activity).apply {
            layout(0, 0, 400, 800)
            addView(TextView(activity).apply { text = "周一 19:36"; layout(150, 0, 250, 30) })
            addView(actionBar)
        }
        actionBar.addView(TextView(activity).apply { text = "Bob"; layout(150, 20, 250, 50) })
        try {
            hook.install()
            installer.callback("onCreateView", root, AssistantChatFragment(activity))
            (0 until actionBar.childCount).map(actionBar::getChildAt)
                .filterIsInstance<TextView>().single { it.text.toString() == "AI分析" }.performClick()
            assertEquals("Bob", request?.title)
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `AI analysis button remains available when realtime cards are disabled`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = android.widget.RelativeLayout(activity)
        var request: com.jev.relationship.ipc.ChatAssistantRequest? = null
        val hook = WechatChatUiHook(
            context = context,
            installer = installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
            onChatAssistantRequest = { value, _ -> request = value; true },
        )
        assertEquals(HookInstallResult.INSTALLED, hook.install())

        val root = FrameLayout(activity).apply {
            layout(0, 0, 400, 800)
            addView(actionBar, FrameLayout.LayoutParams(400, 56))
            addView(ChatListMarker(activity))
        }
        val fragment = AssistantChatFragment(activity)
        installer.callback("onCreateView", root, fragment)
        actionBar.getChildAt(0).performClick()

        val aiButton = (0 until actionBar.childCount).map(actionBar::getChildAt)
            .filterIsInstance<TextView>().single { it.text.toString() == "AI分析" }
        assertTrue(aiButton.isEnabled)
        aiButton.performClick()

        assertEquals("alice", request?.conversationId)
        assertTrue(request?.question == null)
        hook.uninstall()
        activity.finish()
    }

    @Test
    fun `toggle hides without discarding analysis and restores it without IPC results`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = RelativeLayout(activity)
        val message = TextView(activity).apply { text = "hello" }
        val host = EmbeddedChatCardHost(activity,
            locator = WechatChatViewLocator(ChatListMarker::class.java.name),
            resolveTarget = { _, _ -> WechatMessageAnchor(message, false, 42L) },
        )
        val hook = WechatChatUiHook(activity, installer,
            hostFactory = { host },
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
        )
        val fragment = AssistantChatFragment(activity)
        val root = FrameLayout(activity).apply {
            addView(actionBar)
            addView(ChatListMarker(activity))
            addView(message)
        }
        JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
        hook.install()
        try {
            installer.callback("onCreateView", root, fragment)
            val cached = result().copy(messageId = "wechat-8.0.72-42",
                conversationHash = WechatMessageAnchorResolver.hash("alice"))
            hook.onAnalysisResult(cached)
            assertTrue(host.cardVisible)
            val toggle = (0 until actionBar.childCount).map(actionBar::getChildAt)
                .filterIsInstance<TextView>().single { it.text.toString() == "Jev" }
            toggle.performClick()
            assertTrue(!host.cardVisible)
            assertEquals("已隐藏解析气泡，缓存已保留", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            toggle.performClick()
            assertTrue("opening the switch must immediately restore the cached card", host.cardVisible)
            assertEquals("已开启解析，优先读取缓存", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertEquals(1, host.createdCardCount)

            toggle.performClick()
            hook.onAnalysisResult(cached)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertTrue("late results must remain hidden while switched off", !host.cardVisible)
            toggle.performClick()
            assertTrue(host.cardVisible)
        } finally {
            JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `long press requests recent sixty regeneration without toggling off`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = RelativeLayout(activity)
        var regeneratedChat: String? = null
        var toggles = 0
        val hook = WechatChatUiHook(activity, installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
            onConversationToggle = { _, _, _ -> toggles++ },
            onRegenerateConversation = { regeneratedChat = it; true },
        )
        JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
        hook.install()
        try {
            val root = FrameLayout(activity).apply { addView(actionBar) }
            installer.callback("onCreateView", root, AssistantChatFragment(activity))
            val toggle = (0 until actionBar.childCount).map(actionBar::getChildAt)
                .filterIsInstance<TextView>().single { it.text.toString() == "Jev" }
            assertTrue(toggle.performLongClick())
            assertEquals("alice", regeneratedChat)
            assertEquals(0, toggles)
            assertTrue(JevConversationAnalysisPrefs.isEnabled(activity, "alice"))
            assertEquals("正在重新解读最近60条消息，成功后更新缓存", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `AI analysis long press persists reply visibility independently without requesting analysis`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val actionBar = RelativeLayout(activity)
        var requests = 0
        val hook = WechatChatUiHook(activity, installer,
            classResolver = { _, _ -> AssistantChatFragment::class.java },
            actionBarResolver = { actionBar },
            onChatAssistantRequest = { _, _ -> requests++; true },
        )
        JevConversationAnalysisPrefs.setReplyHidden(activity, "alice", false)
        JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
        hook.install()
        try {
            val root = FrameLayout(activity).apply { addView(actionBar) }
            installer.callback("onCreateView", root, AssistantChatFragment(activity))
            val ai = (0 until actionBar.childCount).map(actionBar::getChildAt)
                .filterIsInstance<TextView>().single { it.text.toString() == "AI分析" }
            assertTrue(ai.performLongClick())
            assertTrue(JevConversationAnalysisPrefs.isReplyHidden(activity, "alice"))
            assertTrue(!JevConversationAnalysisPrefs.isReplyHidden(activity, "bob"))
            assertTrue(JevConversationAnalysisPrefs.isEnabled(activity, "alice"))
            assertEquals("已隐藏回复建议，长按 AI分析 恢复", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertTrue(ai.performLongClick())
            assertTrue(!JevConversationAnalysisPrefs.isReplyHidden(activity, "alice"))
            assertEquals(0, requests)
        } finally {
            JevConversationAnalysisPrefs.setReplyHidden(activity, "alice", false)
            hook.uninstall()
            activity.finish()
        }
    }

    @Test
    fun `recreated chat view restores same conversation results without another analysis`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val installer = RecordingInstaller()
        val hosts = mutableListOf<EmbeddedChatCardHost>()
        var message = TextView(activity).apply { text = "hello" }
        val hook = WechatChatUiHook(activity, installer,
            hostFactory = {
                EmbeddedChatCardHost(activity,
                    locator = WechatChatViewLocator(ChatListMarker::class.java.name),
                    resolveTarget = { _, _ -> WechatMessageAnchor(message, false, 42L) },
                ).also(hosts::add)
            },
            classResolver = { _, _ -> AssistantChatFragment::class.java },
        )
        val fragment = AssistantChatFragment(activity)
        fun root() = FrameLayout(activity).apply {
            addView(ChatListMarker(activity))
            addView(message)
        }
        JevConversationAnalysisPrefs.setEnabled(activity, "alice", true)
        hook.install()
        try {
            installer.callback("onCreateView", root(), fragment)
            hook.onAnalysisResult(result().copy(messageId = "wechat-8.0.72-42",
                conversationHash = WechatMessageAnchorResolver.hash("alice")))
            assertTrue(hosts.last().cardVisible)
            installer.callback("onDestroyView", null, fragment)
            message = TextView(activity).apply { text = "hello" }
            installer.callback("onCreateView", root(), fragment)
            assertTrue("view recreation must retain the result", hosts.last().cardVisible)
        } finally {
            hook.uninstall()
            activity.finish()
        }
    }

    private fun result() = IpcAnalysisResult(
        messageId = "m1",
        conversationHash = "c1",
        textHash = WechatMessageAnchorResolver.hash("hello"),
        isOutgoing = false,
        emotion = "平稳",
        intents = emptyList(),
        riskLevel = 1,
        suggestion = "继续观察",
        historyId = 7L,
    )

    class ChatListMarker(context: Context) : FrameLayout(context)

    class FakeChatFragment {
        fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? = null
        fun onResume() = Unit
        fun onPause() = Unit
        fun onDestroyView() = Unit
        fun onDestroy() = Unit
    }

    class PartialChatFragment {
        fun onResume() = Unit
    }

    class AssistantChatFragment(private val activity: Activity) {
        var conversationId = "alice"
        var view: View? = null
        fun getStringExtra(name: String): String? = if (name == "Chat_User") conversationId else null
        fun getActivity(): Activity = activity
        fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? = null
        fun onResume() = Unit
        fun onPause() = Unit
        fun onDestroyView() = Unit
        fun onDestroy() = Unit
    }

    open class InheritedChatFragment : FragmentLifecycleBase()

    open class FragmentLifecycleBase {
        fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? = null
        fun onResume() = Unit
        fun onPause() = Unit
        fun onDestroyView() = Unit
        fun onDestroy() = Unit
    }

    private class RecordingInstaller : WechatUiHookInstaller {
        private val callbacks = mutableMapOf<String, (Any?, Any?) -> Unit>()
        var installedCount: Int = 0
            private set

        override fun install(target: Method, after: (Any?, Any?) -> Unit): WechatHookHandle {
            installedCount += 1
            callbacks[target.name] = after
            return WechatHookHandle { callbacks.remove(target.name) }
        }

        fun callback(name: String, result: Any?, owner: Any? = null) {
            callbacks[name]?.invoke(owner, result)
        }
    }
}
