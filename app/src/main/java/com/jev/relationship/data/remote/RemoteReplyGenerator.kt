package com.jev.relationship.data.remote

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.AnalysisContext
import com.jev.relationship.domain.ReplyGenerator

class RemoteReplyGenerator(
    private val client: OpenAiCompatibleClient,
    private val settingsRepository: SettingsRepository,
) : ReplyGenerator {
    override suspend fun generate(
        conversation: Conversation,
        analysis: AnalysisResult,
    ): List<ReplySuggestion> {
        return generate(AnalysisContext(conversation), analysis)
    }

    override suspend fun generate(
        context: AnalysisContext,
        analysis: AnalysisResult,
    ): List<ReplySuggestion> {
        val content = client.complete(
            settings = settingsRepository.currentProviderSettings().replySettings(),
            systemPrompt = "你是关系沟通回复助手。只输出 JSON：replies 数组，每项包含 tone(gentle/humorous/serious) 和 text。不要输出 Markdown。",
            userPrompt = RemotePromptBuilder.replyUserPrompt(context, analysis.suggestion),
        )
        return RemotePayloadParser.parseReplies(content)
    }
}
