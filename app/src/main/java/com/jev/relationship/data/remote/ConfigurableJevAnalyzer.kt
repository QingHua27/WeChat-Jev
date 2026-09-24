package com.jev.relationship.data.remote

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.AnalysisContext
import com.jev.relationship.domain.JevAnalyzer

class ConfigurableJevAnalyzer(
    private val settingsRepository: SettingsRepository,
    private val remote: JevAnalyzer,
    private val fallback: JevAnalyzer,
) : JevAnalyzer {
    override suspend fun analyze(conversation: Conversation): AnalysisResult =
        analyze(AnalysisContext(conversation))

    override suspend fun analyze(context: AnalysisContext): AnalysisResult =
        if (settingsRepository.currentProviderSettings().isConfigured) remote.analyze(context)
        else fallback.analyze(context)
}
