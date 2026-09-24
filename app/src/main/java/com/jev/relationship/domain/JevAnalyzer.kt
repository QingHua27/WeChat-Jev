package com.jev.relationship.domain

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation

interface JevAnalyzer {
    suspend fun analyze(conversation: Conversation): AnalysisResult

    suspend fun analyze(context: AnalysisContext): AnalysisResult = analyze(context.conversation)
}
