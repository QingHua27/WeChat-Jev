package com.jev.relationship.xposed.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult
import java.security.MessageDigest

data class WechatMessageAnchor(val message: View, val outgoing: Boolean, val localMessageId: Long? = null)

class WechatMessageAnchorResolver(
    private val resourceName: (View) -> String? = { view ->
        if (view.id == View.NO_ID) null else runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    },
    private val localMessageId: (View) -> Long? = WechatMessageRecordIdReader::read,
) {
    private data class Candidate(
        val view: View,
        val bounds: Rect,
        val outgoing: Boolean,
        val resourceName: String?,
        val localMessageId: Long?,
        val textHashes: Set<String>,
    )

    fun resolve(container: ViewGroup, result: IpcAnalysisResult): WechatMessageAnchor? {
        return resolveFromCandidates(result, collectCandidates(container))
    }

    /** Resolves many cached results from one hierarchy traversal. */
    fun resolveAll(
        container: ViewGroup,
        results: Collection<IpcAnalysisResult>,
    ): Map<String, WechatMessageAnchor> {
        if (results.isEmpty()) return emptyMap()
        val candidates = collectCandidates(container)
        return results.mapNotNull { result ->
            resolveFromCandidates(result, candidates)?.let { result.messageId to it }
        }.toMap()
    }

    private fun collectCandidates(container: ViewGroup): List<Candidate> {
        val avatars = mutableListOf<Rect>()
        fun collectAvatars(view: View) {
            if (view.visibility != View.VISIBLE || view is JevEmbeddedAnalysisCardView) return
            if (resourceName(view) == "c34") {
                val bounds = Rect(0, 0, view.width, view.height)
                container.offsetDescendantRectToMyCoords(view, bounds)
                avatars += bounds
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) collectAvatars(view.getChildAt(index))
            }
            collectAvatars(container)
        val candidates = mutableListOf<Candidate>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f || view is JevEmbeddedAnalysisCardView || view is EditText) return
            val isNeat = view.javaClass.name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
                view.javaClass.name == "com.tencent.neattextview.textview.view.NeatTextView"
            if (view is TextView || isNeat) {
                val bounds = Rect(0, 0, view.width, view.height)
                container.offsetDescendantRectToMyCoords(view, bounds)
                val avatar = avatars.filter { kotlin.math.abs(it.top - bounds.top) <= it.height() / 2 }
                    .minByOrNull { kotlin.math.abs(it.top - bounds.top) }
                val outgoing = (avatar ?: bounds).centerX() > container.width / 2
                val hashes = texts(view, isNeat)
                    .filter { !it.trim().matches(VOICE_DURATION) && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it) }
                    .mapTo(mutableSetOf(), ::hash)
                if (!bounds.isEmpty && bounds.bottom > 0 && bounds.top < container.height && hashes.isNotEmpty()) {
                    candidates += Candidate(
                        view = view,
                        bounds = bounds,
                        outgoing = outgoing,
                        resourceName = resourceName(view),
                        localMessageId = localMessageId(view),
                        textHashes = hashes,
                    )
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(container)
        return candidates
    }

    fun resolveCached(
        container: ViewGroup,
        results: Map<String, IpcAnalysisResult>,
    ): Map<String, WechatMessageAnchor> {
        if (results.isEmpty()) return emptyMap()
        val candidates = collectCandidates(container)
        return candidates.mapNotNull { it.localMessageId }.distinct().mapNotNull { id ->
            val result = results["wechat-8.0.72-$id"] ?: return@mapNotNull null
            resolveFromCandidates(result, candidates)?.let { result.messageId to it }
        }.toMap()
    }

    /** A bound RecyclerView row has an ID and text before it has any bounds. */
    fun resolveBoundRow(row: ViewGroup, results: Map<String, IpcAnalysisResult>): Map<String, WechatMessageAnchor> {
        val matches = linkedMapOf<String, WechatMessageAnchor>()
        val ambiguous = mutableSetOf<String>()
        fun visit(view: View) {
            if (view is JevEmbeddedAnalysisCardView || view is EditText || view.visibility == View.GONE) return
            val id = localMessageId(view)
            if (id != null) {
                val key = "wechat-8.0.72-$id"
                val result = results[key]
                if (result != null && !result.isOutgoing && matchesPrimaryText(view, result.textHash) == true) {
                    if (matches.put(key, WechatMessageAnchor(view, false, id)) != null) ambiguous += key
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(row)
        ambiguous.forEach(matches::remove)
        return matches
    }

    private fun resolveFromCandidates(
        result: IpcAnalysisResult,
        candidates: List<Candidate>,
    ): WechatMessageAnchor? {
        val matches = candidates.filter { it.outgoing == result.isOutgoing && result.textHash in it.textHashes }
        val bubbles = matches.filter { it.resourceName == "c3u" }
        val ordered = bubbles.ifEmpty { matches }
            .sortedWith(compareBy<Candidate> { it.bounds.top }.thenBy { it.bounds.left })
        val stableId = result.messageId.removePrefix("wechat-8.0.72-").toLongOrNull()
        val identified = stableId?.let { id -> ordered.filter { it.localMessageId == id } }.orEmpty()
        val target = when {
            identified.size == 1 -> identified.single()
            identified.size > 1 -> return null
            stableId != null && ordered.any { it.localMessageId != null } -> return null
            else -> ordered.getOrNull(result.messageOccurrence)
        } ?: return null
        return WechatMessageAnchor(target.view, result.isOutgoing, target.localMessageId)
    }

    fun matchesText(view: View, textHash: String): Boolean = texts(view,
        view.javaClass.name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
            view.javaClass.name == "com.tencent.neattextview.textview.view.NeatTextView",
    ).any { hash(it) == textHash && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it) }

    /** Checks the message's primary text without building accessibility nodes. */
    fun matchesPrimaryText(view: View, textHash: String): Boolean? {
        val text = when {
            view is TextView -> view.text?.toString()
            view.javaClass.name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
                view.javaClass.name == "com.tencent.neattextview.textview.view.NeatTextView" ->
                runCatching { view.javaClass.getMethod("a").invoke(view) as? CharSequence }
                    .getOrNull()?.toString()
            else -> return null
        } ?: return false
        return hash(text) == textHash && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(text)
    }

    private fun texts(view: View, isNeat: Boolean): List<String> = buildList {
        if (view is TextView) view.text?.toString()?.let(::add)
        // Verified on WeChat 8.0.72: NeatTextView.a() is the raw CharSequence getter.
        if (isNeat) (runCatching { view.javaClass.getMethod("a").invoke(view) }.getOrNull() as? CharSequence)?.toString()?.let(::add)
        view.contentDescription?.toString()?.let(::add)
        val node = runCatching { view.createAccessibilityNodeInfo() }.getOrNull()
        try {
            node?.text?.toString()?.let(::add)
            node?.contentDescription?.toString()?.let(::add)
        } finally { node?.recycle() }
    }.filter(String::isNotBlank)

    companion object {
        private val VOICE_DURATION = Regex("\\d{1,4}[\"”]")
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.trim().replace(Regex("\\s+"), " ").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
