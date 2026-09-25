package com.jev.relationship.xposed.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.View
import com.jev.relationship.ipc.IpcAnalysisResult

class EmbeddedChatCardHost(
    private val context: Context,
    private val locator: WechatChatViewLocator = WechatChatViewLocator(),
    private val resolveTarget: (ViewGroup, IpcAnalysisResult) -> WechatMessageAnchor?,
    private val cardFactory: (Context) -> JevEmbeddedAnalysisCardView = ::JevEmbeddedAnalysisCardView,
    private val readLocalMessageId: (View) -> Long? = WechatMessageRecordIdReader::read,
    private val resolveTargets: ((ViewGroup, Collection<IpcAnalysisResult>) -> Map<String, WechatMessageAnchor>)? = null,
    private val maxCachedResults: Int = 128,
    private val resolvePreloadedTargets: ((ViewGroup, Map<String, IpcAnalysisResult>) -> Map<String, WechatMessageAnchor>)? = null,
) {
    fun prepareRowForMeasure(row: View) {
        if (!fastCacheDisplay || !active || !analysisEnabled || destroyed || results.isEmpty() ||
            row.parent !== chatList || row !is ViewGroup) return
        if (!measurementPreparationLogged) {
            measurementPreparationLogged = true
            Log.i(TAG, "cached row preparation active before native measurement; list=${chatList?.javaClass?.name}")
        }
        // Remove a previous owner's card before the recycled row is measured.
        // Keep the native message intact and never expose a different message's result.
        rendered.values.forEach { entry ->
            val anchor = entry.anchor
            val value = entry.renderedResult
            if (anchor != null && value != null && isDescendant(anchor, row) && !hasStableIdentity(entry, value)) {
                removeEntry(entry)
            }
        }
        anchorTextResolver.resolveBoundRow(row, results).forEach { (id, target) ->
            val value = results[id] ?: return@forEach
            val entry = rendered.getOrPut(id) {
                RenderedAnalysis(cardFactory(context).also { createdCardCount++ }, EmbeddedChatMessageInserter())
            }
            entry.view.applyTheme(row.resources.configuration.uiMode)
            entry.invalidated = false
            renderEntry(entry, value)
            val oldParent = entry.view.parent
            val mounted = entry.inserter.insertBelow(target.message, entry.view, false)
            if (oldParent !== entry.view.parent) {
                layoutRevision++
            }
            if (mounted) {
                entry.anchor = target.message
                entry.localMessageId = target.localMessageId
            }
            setEntryVisible(entry, mounted)
        }
        trimMemoryCache()
    }
    private var fastCacheDisplay = false
    private var measurementPreparationLogged = false

    fun setFastCacheDisplay(enabled: Boolean) {
        if (fastCacheDisplay == enabled) return
        fastCacheDisplay = enabled
        trimMemoryCache()
        renderNow()
    }
    private data class RenderedAnalysis(
        val view: JevEmbeddedAnalysisCardView,
        val inserter: EmbeddedChatMessageInserter,
        var anchor: View? = null,
        var localMessageId: Long? = null,
        var renderedResult: IpcAnalysisResult? = null,
        var invalidated: Boolean = false,
    )

    private val results = LinkedHashMap<String, IpcAnalysisResult>(16, 0.75f, true)
    private val rendered = linkedMapOf<String, RenderedAnalysis>()
    private val anchorTextResolver = WechatMessageAnchorResolver(localMessageId = readLocalMessageId)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var container: ViewGroup? = null
    private var chatList: ViewGroup? = null
    private var rootView: View? = null
    private var active = true
    private var analysisEnabled = true
    private var scrolling = false
    private var viewportDirty = false
    private var layoutRevision = 0L
    private var lastDiagnosticAt = 0L
    private var retryIndex = 0
    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!active || destroyed || scrolling) return
            renderNow()
            retryIndex++
            if (retryIndex < RENDER_RETRY_DELAYS_MS.size && hasPendingMounts()) {
                mainHandler.postDelayed(this, RENDER_RETRY_DELAYS_MS[retryIndex])
            }
        }
    }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        scheduleViewportRender()
    }
    private val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
        scrolling = true
        mainHandler.removeCallbacks(retryRunnable)
        scheduleViewportRender()
    }
    private val viewportRenderRunnable = Runnable {
        scrolling = false
        if (active && !destroyed && viewportDirty) {
            viewportDirty = false
            renderNow()
        }
    }
    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        if (!active || destroyed || !viewportDirty) {
            true
        } else {
            viewportDirty = false
            val before = layoutRevision
            // Recycler rows can be rebound during layout. Restore paid results before
            // drawing the shortened row, rather than after a scroll-idle timeout.
            renderNow()
            if (layoutRevision != before) {
                rootView?.requestLayout()
                false
            } else {
                true
            }
        }
    }

    var createdCardCount: Int = 0
        private set
    internal val cachedResultCount: Int get() = results.size
    internal val retainedCardCount: Int get() = rendered.size
    var destroyed: Boolean = false
        private set

    val cardVisible: Boolean
        get() = rendered.values.any { it.view.visibility == View.VISIBLE }

    fun onAnalysisResults(values: Collection<IpcAnalysisResult>) {
        var changed = false
        values.forEach { if (rememberResult(it)) changed = true }
        if (!changed) return
        Log.i(TAG, "analysis results received batch=${values.size} count=${results.size}")
        if (scrolling) scheduleViewportRender() else renderNow()
        trimMemoryCache()
        if (!scrolling) rootView?.let(::scheduleRenderRetries)
    }

    fun onAnalysisResult(value: IpcAnalysisResult) = onAnalysisResults(listOf(value))

    private fun rememberResult(value: IpcAnalysisResult): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDiagnosticAt > 3_000L) {
            lastDiagnosticAt = now
            val entry = rendered[value.messageId]
            Log.i(TAG, "surface active=$active enabled=$analysisEnabled fast=$fastCacheDisplay destroyed=$destroyed " +
                "cached=${results.size} rootAttached=${rootView?.isAttachedToWindow} listShown=${chatList?.isShown} " +
                "listChildren=${chatList?.childCount} entry=${entry != null} invalid=${entry?.invalidated} " +
                "identity=${entry?.let { hasStableIdentity(it, value) }} cardShown=${entry?.view?.isShown} " +
                "cardSize=${entry?.view?.width}x${entry?.view?.height} " +
                "anchorShown=${entry?.anchor?.isShown} bound=${entry?.inserter?.boundMessage != null}")
        }
        if (destroyed || value.isOutgoing) return false
        if (results[value.messageId] == value) {
            // Identical data can arrive after WeChat has rebuilt its native rows.
            // Skip a healthy mount, but coalesce a local repair of stale UI state.
            val host = (chatList?.takeIf { it.childCount > 0 } ?: container)
            val entry = rendered[value.messageId]
            if (entry == null || host == null) scheduleViewportRender()
            else if (!isStableMountedEntry(host, entry, value) &&
                (entry.view.parent == null || !isDescendant(entry.view, host) ||
                    entry.inserter.boundMessage?.parent !== entry.view.parent)) scheduleViewportRender()
            return false
        }
        results[value.messageId] = value
        rendered[value.messageId]?.invalidated = false
        return true
    }

    fun attach(root: View) {
        if (destroyed) return
        val located = locator.locate(root)
        val sameHierarchy = rootView === root && container === located?.container &&
            chatList === located?.chatList
        rootView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        rootView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        rootView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        if (!sameHierarchy) removeEmbeddedViews()
        rootView = root
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        container = located?.container
        chatList = located?.chatList as? ViewGroup
        if (sameHierarchy) return
        renderNow()
        scheduleRenderRetries(root)
    }

    fun onResume() {
        mainHandler.removeCallbacks(viewportRenderRunnable)
        active = true
        scrolling = false
        viewportDirty = false
        renderNow()
    }

    fun onPause() {
        active = false
        viewportDirty = false
        mainHandler.removeCallbacks(viewportRenderRunnable)
        mainHandler.removeCallbacks(retryRunnable)
        removeEmbeddedViews()
    }

    /** Visibility is independent of cached results and fragment lifecycle. */
    fun setAnalysisEnabled(value: Boolean) {
        if (analysisEnabled == value) return
        analysisEnabled = value
        renderNow()
    }

    fun clear() {
        viewportDirty = false
        mainHandler.removeCallbacks(viewportRenderRunnable)
        mainHandler.removeCallbacks(retryRunnable)
        results.clear()
        removeEmbeddedViews()
        rendered.clear()
    }

    fun renderNow() {
        // A fragment may resume before its RecyclerView exists, or retain its
        // root while WeChat replaces the list. Rediscover that late hierarchy.
        val root = rootView
        val list = chatList
        if (root != null && (list == null || !isDescendant(list, root))) {
            locator.locate(root)?.let { located ->
                chatList = located.chatList as? ViewGroup
                container = located.container
            }
        }
        val host = (chatList?.takeIf { it.childCount > 0 } ?: container) ?: return
        if (!active || !analysisEnabled) {
            removeEmbeddedViews()
            return
        }
        val preloadedTargets = if (fastCacheDisplay) {
            resolvePreloadedTargets?.invoke(host, results)
                ?: resolveTargets?.invoke(host, results.values)
                ?: results.values.mapNotNull { value -> resolveTarget(host, value)?.let { value.messageId to it } }.toMap()
        } else null
        // Retain data for the whole conversation, but only allocate views for rows
        // that exist. Stable-ID lookup keeps scrolling independent of cache size.
        val workingResults = if (preloadedTargets != null) {
            (preloadedTargets.keys + rendered.keys).mapNotNull { id -> results[id]?.let { id to it } }.toMap()
        } else results
        val entries = workingResults.mapValues { (messageId, _) ->
            rendered.getOrPut(messageId) {
                RenderedAnalysis(
                    view = cardFactory(context).also { created -> createdCardCount += 1 },
                    inserter = EmbeddedChatMessageInserter(),
                )
            }.also { it.view.applyTheme(host.resources.configuration.uiMode) }
        }
        val stableIds = workingResults.filter { (messageId, value) ->
            isStableMountedEntry(host, entries.getValue(messageId), value)
        }.keys
        val needsResolution = workingResults.filterKeys { it !in stableIds }
        val resolvedTargets = preloadedTargets ?: if (needsResolution.isEmpty()) null else resolveTargets?.invoke(host, needsResolution.values)

        workingResults.forEach { (messageId, value) ->
            val entry = entries.getValue(messageId)
            if (messageId in stableIds) {
                renderEntry(entry, value)
                setEntryVisible(entry, true)
                return@forEach
            }
            val target = if (preloadedTargets != null || resolveTargets != null) {
                resolvedTargets?.get(messageId)
            } else {
                runCatching { resolveTarget(host, value) }.getOrNull()
            }
            val expectedLocalId = value.messageId.removePrefix("wechat-8.0.72-").toLongOrNull()
            val sameStableMessage = expectedLocalId != null && target?.localMessageId == expectedLocalId
            // A RecyclerView rebinds the same database message to a new View.
            // Reattach only when the row itself proves the stable local ID.
            if ((entry.invalidated && !sameStableMessage) ||
                (target != null && entry.anchor != null && entry.anchor !== target.message && !sameStableMessage)
            ) {
                invalidateEntry(entry)
                return@forEach
            }
            if (sameStableMessage) entry.invalidated = false
            if (target == null) {
                val bound = entry.inserter.boundMessage
                // A detached/recycled-list scrap row still owns its measured card
                // while its database identity and original text remain unchanged.
                if (hasStableIdentity(entry, value)) return@forEach
                if (expectedLocalId == null && bound != null && isDescendant(bound, host) &&
                    !bound.getGlobalVisibleRect(android.graphics.Rect()) &&
                    runCatching { anchorTextResolver.matchesText(bound, value.textHash) }.getOrDefault(false)) {
                    return@forEach
                }
                removeEntry(entry)
                return@forEach
            }
            renderEntry(entry, value)
            val previousParent = entry.view.parent
            val inserted = runCatching {
                entry.inserter.insertBelow(target.message, entry.view, target.outgoing)
            }.getOrDefault(false)
            if (entry.view.parent !== previousParent) layoutRevision++
            if (inserted) {
                entry.anchor = target.message
                entry.localMessageId = target.localMessageId
            }
            setEntryVisible(entry, inserted)
        }
        if (!hasPendingMounts()) mainHandler.removeCallbacks(retryRunnable)
        trimMemoryCache()
    }

    private fun trimMemoryCache() {
        if (fastCacheDisplay) {
            for (id in rendered.keys.toList()) {
                if (rendered.size <= maxCachedResults) break
                if (rendered[id]?.view?.isAttachedToWindow == true) continue
                rendered.remove(id)?.let(::removeEntry)
            }
            return
        }
        if (results.size <= maxCachedResults) return
        for (id in results.keys.toList()) {
            if (results.size <= maxCachedResults) break
            val entry = rendered[id]
            // Even an offscreen attached row contributes to the list's geometry.
            // Keep it until WeChat detaches it; never resize live rows to hit a cap.
            if (entry?.view?.isAttachedToWindow == true) continue
            rendered.remove(id)?.let(::removeEntry)
            results.remove(id)
        }
    }

    fun destroy() {
        rootView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        rootView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        rootView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        mainHandler.removeCallbacksAndMessages(null)
        removeEmbeddedViews()
        rendered.clear()
        container = null
        chatList = null
        rootView = null
        results.clear()
        active = false
        destroyed = true
    }

    fun containerChildCount(): Int = container?.childCount ?: 0

    private fun removeEmbeddedViews() {
        rendered.values.forEach(::removeEntry)
    }

    private fun removeEntry(entry: RenderedAnalysis) {
        if (entry.view.parent != null) layoutRevision++
        entry.inserter.remove()
        setEntryVisible(entry, false)
    }

    private fun invalidateEntry(entry: RenderedAnalysis) {
        removeEntry(entry)
        entry.invalidated = true
    }

    private fun renderEntry(entry: RenderedAnalysis, value: IpcAnalysisResult) {
        if (entry.renderedResult == value) return
        if (entry.view.parent != null) layoutRevision++
        entry.view.render(value)
        entry.renderedResult = value
    }

    private fun setEntryVisible(entry: RenderedAnalysis, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (entry.view.visibility == visibility) return
        if (entry.view.parent != null) layoutRevision++
        entry.view.visibility = visibility
    }

    private fun isDescendant(view: View, host: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === host) return true
            parent = parent.parent
        }
        return false
    }

    private fun isStableMountedEntry(
        host: ViewGroup,
        entry: RenderedAnalysis,
        value: IpcAnalysisResult,
    ): Boolean {
        return entry.anchor?.let { isDescendant(it, host) } == true &&
            isDescendant(entry.view, host) && hasStableIdentity(entry, value)
    }

    private fun hasStableIdentity(entry: RenderedAnalysis, value: IpcAnalysisResult): Boolean {
        val anchor = entry.anchor ?: return false
        val expectedLocalId = value.messageId.removePrefix("wechat-8.0.72-").toLongOrNull()
        return !entry.invalidated &&
            expectedLocalId != null &&
            entry.localMessageId == expectedLocalId &&
            entry.inserter.boundMessage === anchor &&
            entry.view.parent != null &&
            (entry.view.parent === anchor.parent || entry.view.parent === anchor) &&
            readLocalMessageId(anchor) == expectedLocalId &&
            anchorTextResolver.matchesPrimaryText(anchor, value.textHash) == true
    }

    private fun scheduleViewportRender() {
        if (!active || destroyed) return
        viewportDirty = true
        mainHandler.removeCallbacks(viewportRenderRunnable)
        mainHandler.postDelayed(viewportRenderRunnable, VIEWPORT_RENDER_DEBOUNCE_MS)
    }

    private fun scheduleRenderRetries(root: View) {
        mainHandler.removeCallbacks(retryRunnable)
        if (!active || destroyed || !analysisEnabled || root !== rootView || !hasPendingMounts()) return
        retryIndex = 0
        mainHandler.postDelayed(retryRunnable, RENDER_RETRY_DELAYS_MS[0])
    }

    private fun hasPendingMounts(): Boolean {
        // Preloaded offscreen data is expected to have no view. Native layout
        // events mount it when its row appears; don't poll the entire history.
        if (fastCacheDisplay) return false
        val host = (chatList?.takeIf { it.childCount > 0 } ?: container) ?: return results.isNotEmpty()
        return results.any { (id, value) ->
            val entry = rendered[id]
            entry == null || entry.view.visibility != View.VISIBLE || !isStableMountedEntry(host, entry, value)
        }
    }

    private companion object {
        const val TAG = "JevEmbedded"
        const val VIEWPORT_RENDER_DEBOUNCE_MS = 80L
        val RENDER_RETRY_DELAYS_MS = longArrayOf(80L, 220L, 500L, 1_000L)
    }

}
