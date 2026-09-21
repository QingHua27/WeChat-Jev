package com.jev.relationship.domain.realtime

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.domain.AnalysisOutput

fun interface ConversationAnalyzer {
    suspend operator fun invoke(conversation: Conversation): AnalysisOutput
}
