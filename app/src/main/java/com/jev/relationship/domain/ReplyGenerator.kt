package com.jev.relationship.domain

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.ReplySuggestion

interface ReplyGenerator {
    suspend fun generate(
        conversation: Conversation,
        analysis: AnalysisResult,
    ): List<ReplySuggestion>

    suspend fun generate(
        context: AnalysisContext,
        analysis: AnalysisResult,
    ): List<ReplySuggestion> = generate(context.conversation, analysis)
}
