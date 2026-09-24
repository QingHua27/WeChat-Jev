package com.jev.relationship.data.remote

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.AnalysisContext
import com.jev.relationship.domain.ReplyGenerator

class ConfigurableReplyGenerator(
    private val settingsRepository: SettingsRepository,
    private val remote: RemoteReplyGenerator,
    private val fallback: ReplyGenerator,
) : ReplyGenerator {
    override suspend fun generate(
        conversation: Conversation,
        analysis: AnalysisResult,
    ): List<ReplySuggestion> = generate(AnalysisContext(conversation), analysis)

    override suspend fun generate(context: AnalysisContext, analysis: AnalysisResult): List<ReplySuggestion> =
        if (!settingsRepository.currentProviderSettings().replySettings().isConfigured) {
            fallback.generate(context, analysis)
        } else {
            runCatching { remote.generate(context, analysis) }
                .getOrElse { fallback.generate(context, analysis) }
        }
}
