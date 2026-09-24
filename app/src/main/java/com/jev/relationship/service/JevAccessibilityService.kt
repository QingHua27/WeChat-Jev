package com.jev.relationship.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.domain.inline.InlineWindowSnapshotBuilder
import com.jev.relationship.domain.inline.InlineWindowSnapshotStore
import com.jev.relationship.domain.inline.RawInlineNodeSnapshot
import com.jev.relationship.domain.source.AccessibilityMessageParser
import com.jev.relationship.domain.source.ConversationSource
import com.jev.relationship.domain.source.ConversationSourceState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class JevAccessibilityService : AccessibilityService(), ConversationSource {
    @Inject
    lateinit var inlineWindowSnapshotStore: InlineWindowSnapshotStore

    private val _state = MutableStateFlow<ConversationSourceState>(ConversationSourceState.Disabled)
    private val overlayPackagePolicy = AccessibilityOverlayPackagePolicy(WECHAT_PACKAGE)
    private var latest: Conversation? = null
    private val viewportHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var viewportRefreshPending = false
    private val refreshViewport = Runnable {
        viewportRefreshPending = false
        publishWindowSnapshot()
    }

    override val state: StateFlow<ConversationSourceState> = _state.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        _state.value = ConversationSourceState.Active
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        val eventPackage = event?.packageName?.toString()
        val activeWindowPackage = runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        if (overlayPackagePolicy.shouldClearSnapshot(eventPackage, activeWindowPackage)) {
            inlineWindowSnapshotStore.clear()
            return
        }
        if (!overlayPackagePolicy.isTargetWindow(eventPackage, activeWindowPackage)) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // RecyclerView also emits zero-distance scroll events after our
            // card changes row height. Those must not invalidate the result.
            if (android.os.Build.VERSION.SDK_INT >= 28 && event.scrollDeltaX == 0 && event.scrollDeltaY == 0) return
            viewportRefreshPending = true
            viewportHandler.removeCallbacks(refreshViewport)
            viewportHandler.postDelayed(refreshViewport, 400L)
            return
        }
        if (viewportRefreshPending) return
        val nodes = event.text.map { text ->
            com.jev.relationship.domain.source.AccessibilityTextNode(
                packageName = WECHAT_PACKAGE,
                text = text.toString(),
                isVisible = true,
            )
        }
        AccessibilityMessageParser.parse(nodes, setOf(WECHAT_PACKAGE))?.let { text ->
            latest = Conversation(text)
        }
        publishWindowSnapshot()
    }

    override fun onInterrupt() {
        inlineWindowSnapshotStore.clear()
        _state.value = ConversationSourceState.Error("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        viewportHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun start() {
        // The system owns the lifecycle. Users enable this service in Android settings.
    }

    override fun stop() {
        disableSelf()
        latest = null
        inlineWindowSnapshotStore.clear()
        _state.value = ConversationSourceState.Disabled
    }

    override fun latestConversation(): Conversation? = latest

    private fun publishWindowSnapshot() {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val rootPackage = root.packageName?.toString()
        if (rootPackage != WECHAT_PACKAGE) return
        val rawNodes = buildList {
            collectNodes(root, this)
        }
        val metrics = resources.displayMetrics
        inlineWindowSnapshotStore.publish(
            InlineWindowSnapshotBuilder.build(
                packageName = WECHAT_PACKAGE,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                timestampMs = System.currentTimeMillis(),
                nodes = rawNodes,
            ),
        )
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<RawInlineNodeSnapshot>,
    ) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        output += RawInlineNodeSnapshot(
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            visibleToUser = node.isVisibleToUser,
        )
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child ->
                try {
                    collectNodes(child, output)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
