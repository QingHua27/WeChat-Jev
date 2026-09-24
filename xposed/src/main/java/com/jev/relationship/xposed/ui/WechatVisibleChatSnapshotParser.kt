package com.jev.relationship.xposed.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.jev.relationship.ipc.ChatTextPolicy
import com.jev.relationship.ipc.IpcVisibleChatConversation
import com.jev.relationship.ipc.IpcVisibleChatMessage

class WechatVisibleChatSnapshotParser(
    private val locator: WechatChatViewLocator = WechatChatViewLocator(),
    private val localMessageIdOf: (View) -> Long? = WechatMessageRecordIdReader::read,
) {
    private data class TextBubble(val text: String, val bounds: Rect, val viewId: String?, val localMessageId: Long?)

    fun parse(root: View, conversationId: String, screenWidth: Int): IpcVisibleChatConversation? {
        if (conversationId.isBlank() || screenWidth <= 0) return null
        val located = locator.locate(root)
        val chatList = located?.chatList as? ViewGroup ?: run {
            android.util.Log.d(TAG, "chat list not located root=${root.javaClass.name}")
            return null
        }
        val avatars = mutableListOf<Rect>()
        val candidates = mutableListOf<TextBubble>()
        collect(chatList, chatList, candidates, avatars)
        android.util.Log.d(TAG, "chat list children=${chatList.childCount} candidates=${candidates.size} avatars=${avatars.size}")
        val textBubbles = candidates.filter { it.viewId == TEXT_BUBBLE_ID }.ifEmpty { candidates }
            .sortedWith(compareBy<TextBubble> { it.bounds.top }.thenBy { it.bounds.left })
            .distinctBy { Triple(it.text, it.bounds.left, it.bounds.top) }
            .takeLast(MAX_VISIBLE_MESSAGES)
            .toMutableList()
        var visibleCharacters = textBubbles.sumOf { it.text.length }
        while (visibleCharacters > MAX_VISIBLE_CHARACTERS && textBubbles.size > 1) {
            visibleCharacters -= textBubbles.removeAt(0).text.length
        }
        if (textBubbles.isEmpty()) return null

        val occurrences = mutableMapOf<Pair<String, Boolean>, Int>()
        val messages = textBubbles.map { bubble ->
            val avatar = avatars.asSequence()
                .filter { kotlin.math.abs(it.top - bubble.bounds.top) <= it.height() / 2 }
                .minByOrNull { kotlin.math.abs(it.top - bubble.bounds.top) }
            val outgoing = avatar?.let { it.centerX() > screenWidth / 2 }
                ?: (screenWidth - bubble.bounds.right < bubble.bounds.left)
            val key = bubble.text to outgoing
            val occurrence = occurrences.getOrDefault(key, 0)
            occurrences[key] = occurrence + 1
            IpcVisibleChatMessage(bubble.text, outgoing, occurrence, bubble.localMessageId)
        }
        val anchor = messages.lastOrNull { !it.isOutgoing } ?: return null
        return IpcVisibleChatConversation(
            conversationId = conversationId,
            conversationText = messages.joinToString("\n") {
                (if (it.isOutgoing) "我：" else "对方：") + it.text
            },
            anchorText = anchor.text,
            anchorIsOutgoing = false,
            messages = messages,
        )
    }

    private fun collect(
        node: View,
        root: ViewGroup,
        bubbles: MutableList<TextBubble>,
        avatars: MutableList<Rect>,
    ) {
        if (node.visibility != View.VISIBLE || node.alpha <= 0f) return
        val bounds = Rect(0, 0, node.width, node.height)
        if (!node.getGlobalVisibleRect(bounds) || bounds.isEmpty) return
        val id = resourceName(node)
        if (id == AVATAR_ID) avatars += Rect(bounds)
        val isNeat = node.javaClass.name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
            node.javaClass.name == "com.tencent.neattextview.textview.view.NeatTextView"
        if (node is TextView || isNeat) {
            val text = extractText(node, isNeat)?.trim().orEmpty()
            if (ChatTextPolicy.isDialogue(text) && !text.matches(VOICE_DURATION) && !text.matches(TIME_ONLY)) {
                bubbles += TextBubble(text, Rect(bounds), id, localMessageIdOf(node))
            }
        }
        if (node is ViewGroup) for (index in 0 until node.childCount) {
            collect(node.getChildAt(index), root, bubbles, avatars)
        }
    }

    private fun extractText(view: View, neat: Boolean): String? = when (view) {
        is TextView -> view.text?.toString()
        else -> if (neat) runCatching { view.javaClass.getMethod("a").invoke(view) as? CharSequence }
            .getOrNull()?.toString() else null
    } ?: view.contentDescription?.toString()

    private fun resourceName(view: View): String? = if (view.id == View.NO_ID) null else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    private companion object {
        const val TAG = "JevVisibleScan"
        const val AVATAR_ID = "c34"
        const val TEXT_BUBBLE_ID = "c3u"
        const val MAX_VISIBLE_MESSAGES = 24
        const val MAX_VISIBLE_CHARACTERS = 24_000
        val VOICE_DURATION = Regex("\\d{1,4}[\"”]")
        val TIME_ONLY = Regex("\\d{1,2}:\\d{2}")
    }
}
