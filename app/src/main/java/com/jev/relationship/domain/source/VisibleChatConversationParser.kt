package com.jev.relationship.domain.source

import com.jev.relationship.domain.inline.RawInlineNodeSnapshot

data class VisibleChatConversation(
    val conversationKey: String,
    val conversationText: String,
    val anchorText: String,
    val anchorIsOutgoing: Boolean,
    val messages: List<VisibleChatMessage> = emptyList(),
)

data class VisibleChatMessage(
    val text: String,
    val isOutgoing: Boolean,
    val occurrence: Int,
    val localMessageId: Long? = null,
)

object VisibleChatConversationParser {
    private data class VoiceRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val CHAT_CONTENT_TOP = 266
    private const val COMPOSER_HEIGHT = 180
    private const val COMPOSER_EXTRA_MARGIN = 180

    fun parse(
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        nodes: List<RawInlineNodeSnapshot>,
    ): VisibleChatConversation? {
        if (packageName != WECHAT_PACKAGE || screenWidth <= 0 || screenHeight <= 0) return null
        val visibleNodes = nodes.asSequence()
            .filter { it.visibleToUser }
            .filter { it.right > it.left && it.bottom > it.top }
            .toList()
        val hasChatComposer = visibleNodes.any { node ->
            node.className.contains("edittext", ignoreCase = true) &&
                (node.viewIdResourceName?.substringAfterLast('/') == "c3t" ||
                    node.bottom > screenHeight - COMPOSER_HEIGHT - COMPOSER_EXTRA_MARGIN)
        }
        if (!hasChatComposer) return null

        val visible = visibleNodes.filter { !it.text.isNullOrBlank() }
        val voiceRegions = visibleNodes
            .filter(::isVoiceMarker)
            .map { node -> VoiceRegion(node.left, node.top, node.right, node.bottom) }

        val conversationKey = visible.asSequence()
            .filter { it.top in 114 until CHAT_CONTENT_TOP }
            .filterNot { isIgnored(it.className) }
            .mapNotNull { it.text?.trim() }
            .filter { it.length >= 2 }
            .maxByOrNull(String::length)
            ?: FALLBACK_CONVERSATION_KEY

        val messageCandidates = visible.asSequence()
            .filter { it.top >= CHAT_CONTENT_TOP && it.bottom <= screenHeight - COMPOSER_HEIGHT }
            .filter { it.className.contains("textview", ignoreCase = true) }
            .filterNot { isIgnored(it.className) }
            .filterNot { isPaymentComponent(it.viewIdResourceName) }
            .filterNot { isVoiceDuration(it, voiceRegions) }
            .filterNot { isTimestamp(it.text.orEmpty()) }
            .filter { com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it.text.orEmpty()) }
            .toList()
        val hasWechatTextBubbleId = messageCandidates.any { node ->
            node.viewIdResourceName?.endsWith("/c3u") == true ||
                node.viewIdResourceName == "c3u"
        }
        val messageNodes = messageCandidates.asSequence()
            .filter { node ->
                !hasWechatTextBubbleId ||
                    node.viewIdResourceName?.endsWith("/c3u") == true ||
                    node.viewIdResourceName == "c3u"
            }
            .sortedWith(compareBy<RawInlineNodeSnapshot> { it.top }.thenBy { it.left })
            .toList()
            .distinctBy { node ->
                node.text?.trim().orEmpty() to node.left to node.top
            }
        val occurrences = mutableMapOf<Pair<String, Boolean>, Int>()
        val messages = messageNodes.mapNotNull { node ->
            val text = node.text?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val avatar = visibleNodes.asSequence()
                .filter { it.viewIdResourceName?.substringAfterLast('/') == "c34" }
                .filter { kotlin.math.abs(it.top - node.top) <= (it.bottom - it.top) / 2 }
                .minByOrNull { kotlin.math.abs(it.top - node.top) }
            val outgoing = avatar?.let { (it.left + it.right) / 2 > screenWidth / 2 }
                ?: (screenWidth - node.right < node.left)
            val key = text to outgoing
            val occurrence = occurrences.getOrDefault(key, 0)
            occurrences[key] = occurrence + 1
            VisibleChatMessage(text, outgoing, occurrence)
        }
        val incomingMessages = messages.filterNot { message -> message.isOutgoing }
        if (incomingMessages.isEmpty()) return null
        val anchor = incomingMessages.last()

        return VisibleChatConversation(
            conversationKey = conversationKey,
            conversationText = messages.joinToString("\n") { it.text },
            anchorText = anchor.text,
            anchorIsOutgoing = anchor.isOutgoing,
            messages = messages,
        )
    }

    private fun isIgnored(className: String): Boolean {
        val normalized = className.lowercase()
        return normalized.contains("edittext") ||
            normalized.contains("button") ||
            normalized.contains("actionbar") ||
            normalized.contains("toolbar") ||
            normalized.contains("recyclerview")
    }

    private fun isTimestamp(text: String): Boolean =
        text.trim().matches(Regex("\\d{1,2}:\\d{2}"))

    private fun isPaymentComponent(viewIdResourceName: String?): Boolean =
        viewIdResourceName?.substringAfterLast('/') in PAYMENT_COMPONENT_IDS

    private fun isVoiceMarker(node: RawInlineNodeSnapshot): Boolean =
        node.viewIdResourceName?.substringAfterLast('/') in VOICE_COMPONENT_IDS ||
            node.text?.trim() == "倍速播放"

    private fun isVoiceDuration(
        node: RawInlineNodeSnapshot,
        voiceRegions: List<VoiceRegion>,
    ): Boolean {
        if (!node.text.orEmpty().trim().matches(VOICE_DURATION_PATTERN)) return false
        val centerX = (node.left + node.right) / 2
        val centerY = (node.top + node.bottom) / 2
        val isInsideKnownVoiceRegion = voiceRegions.any { region ->
            centerX in region.left..region.right && centerY in region.top..region.bottom
        }
        val hasVoiceBubbleId = node.viewIdResourceName?.substringAfterLast('/') in VOICE_BUBBLE_IDS
        return isInsideKnownVoiceRegion || hasVoiceBubbleId
    }

    private const val FALLBACK_CONVERSATION_KEY = "wechat-chat"
    private val PAYMENT_COMPONENT_IDS = setOf("abq", "abm")
    private val VOICE_COMPONENT_IDS = setOf("c5m", "c5l")
    private val VOICE_BUBBLE_IDS = setOf("c3u")
    private val VOICE_DURATION_PATTERN = Regex("\\d{1,4}[\"”]")
}
