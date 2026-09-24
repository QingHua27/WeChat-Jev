package com.jev.relationship.domain.inline

import com.jev.relationship.domain.AnalysisOutput

data class InlineMessageAnchor(
    val messageId: String,
    val conversationHash: String,
    val textHash: String,
    val isOutgoing: Boolean,
)

data class InlineNodeSnapshot(
    val packageName: String,
    val className: String,
    val textHash: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val visibleToUser: Boolean,
)

data class InlineCardPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

data class InlineAnalysisCard(
    val anchor: InlineMessageAnchor,
    val output: AnalysisOutput,
    val updatedAt: Long,
    val historyId: Long? = null,
)
