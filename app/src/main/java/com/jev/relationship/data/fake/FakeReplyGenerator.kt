package com.jev.relationship.data.fake

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.ReplyTone
import com.jev.relationship.domain.ReplyGenerator

class FakeReplyGenerator : ReplyGenerator {
    override suspend fun generate(
        conversation: Conversation,
        analysis: AnalysisResult,
    ): List<ReplySuggestion> = listOf(
        ReplySuggestion(ReplyTone.Gentle, "我听到了，你愿意和我说说真正介意的地方吗？"),
        ReplySuggestion(ReplyTone.Humorous, "被你发现了，我先认真补课一下。"),
        ReplySuggestion(ReplyTone.Serious, "这件事对你很重要，我想先听完整再回应。"),
    )
}

