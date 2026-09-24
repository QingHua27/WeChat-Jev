package com.jev.relationship.core.model

import com.jev.relationship.domain.AnalysisOutput

data class SavedAnalysis(
    val id: Long,
    val conversation: Conversation,
    val output: AnalysisOutput,
    val createdAt: Long,
)

