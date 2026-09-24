package com.jev.relationship.xposed.ui

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcVisibleChatConversation
import com.jev.relationship.ipc.ChatAssistantRequest
import com.jev.relationship.ipc.ChatAssistantResult
import com.jev.relationship.xposed.hook.HookInstallResult
import com.jev.relationship.xposed.hook.LibXposedUiHookInstaller
import com.jev.relationship.xposed.hook.WechatHookHandle
import com.jev.relationship.xposed.hook.WechatUiHookInstaller
import java.lang.reflect.Method

class WechatChatUiHook(
    private val context: Context,
    private val installer: WechatUiHookInstaller,
    private val hostFactory: (Context) -> EmbeddedChatCardHost = { appContext ->
        val anchorResolver = WechatMessageAnchorResolver()
        EmbeddedChatCardHost(
            context = appContext,
            resolveTarget = anchorResolver::resolve,
            resolveTargets = anchorResolver::resolveAll,
        )
    },
    private val classResolver: (String, ClassLoader) -> Class<*> = { name, loader ->
        Class.forName(name, false, loader)
    },
    private val onConversationToggle: (String, String, Boolean) -> Unit = { _, _, _ -> },
    private val onVisibleChat: (IpcVisibleChatConversation) -> Unit = {},
    private val onChatAssistantRequest: (ChatAssistantRequest, (ChatAssistantResult) -> Unit) -> Boolean = { _, _ -> false },
    private val onCancelChatAssistantRequest: (String) -> Unit = {},
    private val actionBarResolver: ((View) -> ViewGroup?)? = null,
    private val onRegenerateConversation: (String) -> Boolean = { false },
    private val visibleParser: WechatVisibleChatSnapshotParser = WechatVisibleChatSnapshotParser(),
    private val onChatResumed: () -> Unit = {},
) {
    constructor(
        context: Context,
        module: io.github.libxposed.api.XposedInterface,
    ) : this(
        context = context,
        installer = LibXposedUiHookInstaller(module),
        onConversationToggle = { conversationId, title, enabled ->
            JevConversationAnalysisPrefs.setEnabled(context, conversationId, enabled)
        },
    )

    private var installed = false
    private val hookHandles = mutableListOf<WechatHookHandle>()
    private var host: EmbeddedChatCardHost? = null
    private var toggleParent: ViewGroup? = null
    private var toggleButton: TextView? = null
    private var assistantButton: TextView? = null
    private var assistantDialog: ChatAssistantDialog? = null
    private var activeFragment: Any? = null
    private var activeConversationId: String = ""
    private var activeConversationTitle: String = ""
    private var activeAnalysisEnabled: Boolean = true
    private val replyBar = WechatReplySuggestionBar()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var snapshotRoot: View? = null
    private var snapshotScheduled = false
    private var chatResumed = false
    private var lastPublishedSnapshot: IpcVisibleChatConversation? = null
    private val snapshotRunnable = Runnable {
        snapshotScheduled = false
        publishVisibleSnapshot()
    }
    private var entrySnapshotPending = false
    private var entryRetryIndex = 0
    private val entrySnapshotRunnable = object : Runnable {
        override fun run() {
            if (!entrySnapshotPending) return
            if (snapshotRoot == null || !activeAnalysisEnabled || publishVisibleSnapshot()) {
                entrySnapshotPending = false
                return
            }
            // WeChat can resume before its message rows are bound. Retry readiness
            // independently of scroll debounce, then stop rather than polling forever.
            if (entryRetryIndex < ENTRY_RETRY_DELAYS_MS.size) {
                mainHandler.postDelayed(this, ENTRY_RETRY_DELAYS_MS[entryRetryIndex++])
            } else {
                entrySnapshotPending = false
            }
        }
    }
    private val snapshotScrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
        scheduleVisibleSnapshot()
    }
    private val pendingResults = linkedMapOf<String, IpcAnalysisResult>()

    @Synchronized
    fun install(): HookInstallResult {
        if (installed) return HookInstallResult.ALREADY_INSTALLED
        val fragmentClass = runCatching {
            classResolver(TARGET_FRAGMENT_CLASS_NAME, context.classLoader)
        }.getOrElse { return HookInstallResult.TARGET_CLASS_UNAVAILABLE }

        val methods = resolveMethods(fragmentClass)
            ?: return HookInstallResult.TARGET_METHOD_UNAVAILABLE
        val handles = mutableListOf<WechatHookHandle>()
        val installSucceeded = runCatching {
            methods.forEach { (method, callback) ->
                handles += installer.install(method, callback)
            }
        }.isSuccess
        if (!installSucceeded) {
            runCatching { handles.forEach(WechatHookHandle::unhook) }
            return HookInstallResult.FAILED
        }
        hookHandles += handles
        installed = true
        return HookInstallResult.INSTALLED
    }

    fun onAnalysisResult(result: IpcAnalysisResult) {
        if (result.isOutgoing) return
        if (activeConversationId.isNotBlank() &&
            result.conversationHash != WechatMessageAnchorResolver.hash(activeConversationId)
        ) return
        pendingResults["${result.conversationHash}:${result.messageId}"] = result
        Log.i(TAG, "analysis result routed pending=${pendingResults.size}")
        host?.setAnalysisEnabled(activeAnalysisEnabled)
        host?.onAnalysisResult(result)
    }

    fun clear() {
        // IPC invalidation hides the surface, but must not discard paid results.
        host?.setAnalysisEnabled(false)
        replyBar.setEnabled(false)
        lastPublishedSnapshot = null
    }

    fun onConnectionRestored() {
        lastPublishedSnapshot = null
        if (!chatResumed) return
        host?.setAnalysisEnabled(activeAnalysisEnabled)
        replyBar.setEnabled(activeAnalysisEnabled)
        requestImmediateSnapshot()
    }

    fun onReplySuggestion(result: com.jev.relationship.ipc.IpcReplySuggestion) {
        if (activeConversationId.isBlank() || result.conversationHash != WechatMessageAnchorResolver.hash(activeConversationId)) return
        replyBar.update(result)
        replyBar.setEnabled(activeAnalysisEnabled)
        snapshotRoot?.let { runCatching { replyBar.attach(it) } }
    }

    fun onRegenerationStatus(conversationId: String, status: String) {
        if (conversationId == activeConversationId && status.isNotBlank()) {
            Toast.makeText(context, status.take(256), Toast.LENGTH_LONG).show()
        }
    }

    @Synchronized
    fun uninstall() {
        cancelSnapshotRequests()
        runCatching { hookHandles.forEach(WechatHookHandle::unhook) }
        hookHandles.clear()
        host?.destroy()
        host = null
        assistantDialog?.dismiss()
        replyBar.detach()
        assistantDialog = null
        pendingResults.clear()
        installed = false
    }

    private fun resolveMethods(
        fragmentClass: Class<*>,
    ): List<Pair<Method, (Any?, Any?) -> Unit>>? {
        fun one(name: String, parameterCount: Int): Method? {
            var current: Class<*>? = fragmentClass
            while (current != null) {
                current.declaredMethods
                    .firstOrNull { it.name == name && it.parameterTypes.size == parameterCount }
                    ?.let {
                        it.isAccessible = true
                        return it
                    }
                current = current.superclass
            }
            return null
        }

        val onCreateView = one("onCreateView", 3) ?: return null
        val onResume = one("onResume", 0) ?: return null
        val onPause = one("onPause", 0) ?: return null
        val onDestroyView = one("onDestroyView", 0) ?: return null
        val onDestroy = one("onDestroy", 0) ?: return null
        return listOf(
            onCreateView to ::handleCreateView,
            onResume to { owner, _ -> handleResume(owner) },
            onPause to { owner, _ -> if (owner === activeFragment) handlePause() },
            onDestroyView to { owner, _ -> if (owner === activeFragment) handleDestroyView() },
            onDestroy to { owner, _ -> if (owner === activeFragment) handleDestroy() },
        )
    }

    private fun handleCreateView(owner: Any?, value: Any?) {
        val root = value as? View ?: return
        activeFragment = owner
        readConversation(owner, root)
        host?.destroy()
        val nextHost = hostFactory(context)
        host = nextHost
        Log.i(TAG, "chat host created pending=${pendingResults.size}")
        nextHost.setAnalysisEnabled(activeAnalysisEnabled)
        restoreCachedResults(nextHost)
        nextHost.attach(root)
        root.post {
            if (host === nextHost && !nextHost.destroyed) {
                nextHost.attach(root)
                if (assistantButton?.isAttachedToWindow != true) installActionButtons(root)
            }
        }
        installActionButtons(root)
        observeVisibleMessages(root)
        attachReplyBar(root)
    }

    private fun handleResume(owner: Any?) {
        chatResumed = true
        lastPublishedSnapshot = null
        activeFragment = owner ?: activeFragment
        val root = runCatching {
            activeFragment?.javaClass?.getMethod("getView")?.invoke(activeFragment) as? View
        }.getOrNull()
        if (root != null) {
            readConversation(activeFragment, root)
            host?.attach(root)
            installActionButtons(root)
            observeVisibleMessages(root)
            attachReplyBar(root)
            requestImmediateSnapshot()
        }
        host?.setAnalysisEnabled(activeAnalysisEnabled)
        host?.onResume()
        replyBar.setResumed(true)
        replyBar.setEnabled(activeAnalysisEnabled)
        onChatResumed()
    }

    private fun handlePause() {
        chatResumed = false
        host?.onPause()
        replyBar.setResumed(false)
        cancelSnapshotRequests()
    }

    private fun handleDestroyView() {
        chatResumed = false
        lastPublishedSnapshot = null
        replyBar.detach()
        snapshotRoot?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnScrollChangedListener(snapshotScrollListener)
        snapshotRoot = null
        cancelSnapshotRequests()
        toggleButton?.let { button -> (button.parent as? ViewGroup)?.removeView(button) }
        assistantButton?.let { button -> (button.parent as? ViewGroup)?.removeView(button) }
        assistantDialog?.dismiss()
        assistantDialog = null
        toggleParent = null
        toggleButton = null
        assistantButton = null
        activeFragment = null
        host?.destroy()
        host = null
    }

    private fun handleDestroy() {
        handleDestroyView()
    }

    private fun readConversation(owner: Any?, root: View) {
        val previousConversationId = activeConversationId
        activeConversationId = runCatching {
            owner?.javaClass?.getMethod("getStringExtra", String::class.java)
                ?.invoke(owner, "Chat_User") as? String
        }.getOrNull().orEmpty()
        activeConversationTitle = findTitle(root).ifBlank { activeConversationId }
        replyBar.setConversation(WechatMessageAnchorResolver.hash(activeConversationId))
        replyBar.setUserHidden(JevConversationAnalysisPrefs.isReplyHidden(context, activeConversationId))
        if (previousConversationId.isNotBlank() && previousConversationId != activeConversationId) {
            host?.clear()
            assistantDialog?.dismiss()
            assistantDialog = null
        }
        activeAnalysisEnabled = activeConversationId.isBlank() ||
            JevConversationAnalysisPrefs.isEnabled(context, activeConversationId)
        if (previousConversationId != activeConversationId) {
            host?.setAnalysisEnabled(activeAnalysisEnabled)
            host?.let(::restoreCachedResults)
        }
    }

    private fun restoreCachedResults(target: EmbeddedChatCardHost) {
        val conversationHash = WechatMessageAnchorResolver.hash(activeConversationId)
        pendingResults.values.filter {
            activeConversationId.isBlank() || it.conversationHash == conversationHash
        }.forEach(target::onAnalysisResult)
    }

    private fun attachReplyBar(root: View) {
        replyBar.setEnabled(activeAnalysisEnabled)
        runCatching { replyBar.attach(root) }
        root.post { if (snapshotRoot === root) runCatching { replyBar.attach(root) } }
    }

    private fun installActionButtons(root: View) {
        val parent = actionBarResolver?.invoke(root) ?: findActionBar(root) ?: return
        toggleButton?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        assistantButton?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val button = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                readConversation(activeFragment, root)
                if (activeConversationId.isBlank()) return@setOnClickListener
                activeAnalysisEnabled = !activeAnalysisEnabled
                lastPublishedSnapshot = null
                JevConversationAnalysisPrefs.setEnabled(context, activeConversationId, activeAnalysisEnabled)
                renderToggleState(this)
                host?.setAnalysisEnabled(activeAnalysisEnabled)
                replyBar.setEnabled(activeAnalysisEnabled)
                onConversationToggle(activeConversationId, activeConversationTitle, activeAnalysisEnabled)
                if (activeAnalysisEnabled) requestImmediateSnapshot() else cancelSnapshotRequests()
                Toast.makeText(context, if (activeAnalysisEnabled) "已开启解析，优先读取缓存"
                    else "已隐藏解析气泡，缓存已保留", Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                readConversation(activeFragment, root)
                if (activeConversationId.isBlank()) {
                    Toast.makeText(context, "尚未识别当前聊天，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@setOnLongClickListener true
                }
                if (!activeAnalysisEnabled) {
                    activeAnalysisEnabled = true
                    JevConversationAnalysisPrefs.setEnabled(context, activeConversationId, true)
                    host?.setAnalysisEnabled(true)
                    replyBar.setEnabled(true)
                    renderToggleState(this)
                    onConversationToggle(activeConversationId, activeConversationTitle, true)
                }
                val sent = onRegenerateConversation(activeConversationId)
                if (sent) scheduleVisibleSnapshot()
                Toast.makeText(context, if (sent) "正在重新解读最近60条消息，成功后更新缓存"
                    else "Jev 未连接，请稍后重试", Toast.LENGTH_LONG).show()
                true
            }
        }
        renderToggleState(button)
        val buttonParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            dp(48),
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
            marginEnd = dp(48)
        }
        parent.addView(button, buttonParams)
        toggleParent = parent
        toggleButton = button

        val assistant = TextView(context).apply {
            gravity = Gravity.CENTER
            text = "AI分析"
            textSize = 12f
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = dp(56)
            isClickable = true
            isFocusable = true
            contentDescription = "分析当前聊天；长按隐藏或恢复回复建议"
            styleToolbarAction(this)
            setOnClickListener { openAssistantDialog(root) }
            setOnLongClickListener {
                readConversation(activeFragment, root)
                if (activeConversationId.isBlank()) return@setOnLongClickListener true
                val hidden = !JevConversationAnalysisPrefs.isReplyHidden(context, activeConversationId)
                JevConversationAnalysisPrefs.setReplyHidden(context, activeConversationId, hidden)
                replyBar.setUserHidden(hidden, this)
                Toast.makeText(context, if (hidden) "已隐藏回复建议，长按 AI分析 恢复"
                    else "已恢复回复建议", Toast.LENGTH_SHORT).show()
                true
            }
        }
        parent.addView(assistant, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            dp(48),
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
            marginStart = dp(56)
        })
        assistantButton = assistant
    }

    private fun openAssistantDialog(root: View) {
        if (root !== snapshotRoot) return
        readConversation(activeFragment, root)
        val conversationId = activeConversationId.takeIf { it.isNotBlank() } ?: return
        if (assistantDialog?.isShowing == true) return
        val activity = findActivity(root.context) ?: return
        assistantDialog = ChatAssistantDialog(
            activity = activity,
            conversationId = conversationId,
            title = activeConversationTitle.ifBlank { conversationId },
            requestAssistant = onChatAssistantRequest,
            cancelRequest = onCancelChatAssistantRequest,
        ).also(ChatAssistantDialog::open)
    }

    private fun findActivity(value: Context): Activity? {
        var current = value
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun findActionBar(root: View): ViewGroup? {
        var found: ViewGroup? = null
        fun visit(view: View) {
            if (found != null) return
            val id = if (view.id == View.NO_ID) null else
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (id == WECHAT_ACTION_BAR_ID && view is ViewGroup) {
                found = view
                return
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        // Search-opened chats can mount their header as a sibling after onCreateView.
        if (found == null && root.rootView !== root) visit(root.rootView)
        return found
    }

    private fun renderToggleState(button: TextView) {
        button.text = "Jev"
        button.contentDescription = (if (activeAnalysisEnabled) "Jev分析已开启，点击关闭" else "Jev分析已关闭，点击开启") +
            "；长按重新解读最近60条消息"
        val density = context.resources.displayMetrics.density
        styleToolbarAction(button)
        val indicator = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (activeAnalysisEnabled) Color.rgb(111, 153, 130) else Color.rgb(128, 128, 128))
            val size = (4 * density).toInt().coerceAtLeast(1)
            setBounds(0, 0, size, size)
        }
        button.compoundDrawablePadding = (5 * density).toInt()
        button.setCompoundDrawablesRelative(null, null, indicator, null)
    }

    private fun styleToolbarAction(button: TextView) {
        val dark = button.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        button.setTextColor(if (dark) Color.rgb(184, 184, 184) else Color.rgb(89, 89, 89))
        val density = button.resources.displayMetrics.density
        val mask = InsetDrawable(GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 8f * density
        }, 0, (6 * density).toInt(), 0, (6 * density).toInt())
        // Transparent at rest; feedback appears only while interacting with the action.
        button.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x24FFFFFF else 0x18000000), null, mask,
        )
    }

    private fun observeVisibleMessages(root: View) {
        if (snapshotRoot === root) return
        snapshotRoot?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnScrollChangedListener(snapshotScrollListener)
        snapshotRoot = root
        root.viewTreeObserver.addOnScrollChangedListener(snapshotScrollListener)
        requestImmediateSnapshot()
    }

    private fun requestImmediateSnapshot() {
        cancelSnapshotRequests()
        if (snapshotRoot == null || activeConversationId.isBlank() || !activeAnalysisEnabled) return
        entrySnapshotPending = true
        entryRetryIndex = 0
        // Run after the lifecycle callback, without imposing the scroll quiet window.
        mainHandler.post(entrySnapshotRunnable)
    }

    private fun cancelSnapshotRequests() {
        mainHandler.removeCallbacks(snapshotRunnable)
        mainHandler.removeCallbacks(entrySnapshotRunnable)
        snapshotScheduled = false
        entrySnapshotPending = false
    }

    private fun scheduleVisibleSnapshot() {
        if (activeConversationId.isBlank() || !activeAnalysisEnabled) return
        if (entrySnapshotPending) return
        snapshotScheduled = true
        mainHandler.removeCallbacks(snapshotRunnable)
        mainHandler.postDelayed(snapshotRunnable, SNAPSHOT_DEBOUNCE_MS)
    }

    private fun publishVisibleSnapshot(): Boolean {
        val root = snapshotRoot ?: return false
        val conversationId = activeConversationId
        if (conversationId.isBlank() || !activeAnalysisEnabled) return false
        val snapshot = runCatching {
            visibleParser.parse(root, conversationId, context.resources.displayMetrics.widthPixels)
        }.onFailure { Log.w(TAG, "visible message scan failed type=${it.javaClass.simpleName}") }
            .getOrNull()
        if (snapshot == null) {
            Log.d(TAG, "visible snapshot empty conversation=${conversationId.hashCode()}")
            return false
        }
        if (snapshot == lastPublishedSnapshot) return true
        lastPublishedSnapshot = snapshot
        Log.i(
            TAG,
            "visible snapshot messages=${snapshot.messages.size} stableIds=${snapshot.messages.count { it.localMessageId != null }} " +
                "conversation=${conversationId.hashCode()}",
        )
        onVisibleChat(snapshot)
        return true
    }

    private fun findTitle(root: View): String {
        val actionBar = actionBarResolver?.invoke(root) ?: findActionBar(root) ?: return ""
        val candidates = mutableListOf<Pair<Int, String>>()
        fun visit(view: View) {
            if (view === toggleButton || view === assistantButton) return
            val text = when (view) {
                is TextView -> view.text?.toString()
                else -> if (view.javaClass.name.contains("NeatTextView")) {
                    runCatching { view.javaClass.getMethod("a").invoke(view) as? CharSequence }
                        .getOrNull()?.toString()
                } else null
            }?.trim().orEmpty()
            if (view.visibility == View.VISIBLE && text.isNotBlank() && text.length <= 32) {
                candidates += view.top to text
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(actionBar)
        return candidates.minByOrNull { it.first }?.second.orEmpty()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        const val TARGET_FRAGMENT_CLASS_NAME = "com.tencent.mm.ui.chatting.ChattingUIFragment"
        private const val TAG = "JevChatUiHook"
        private const val WECHAT_ACTION_BAR_ID = "dln"
        private const val SNAPSHOT_DEBOUNCE_MS = 350L
        private val ENTRY_RETRY_DELAYS_MS = longArrayOf(80L, 220L, 500L)
    }
}
