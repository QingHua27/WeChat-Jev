package com.jev.relationship.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SettingsRepository {
    val providerSettings: Flow<ProviderSettings>
    val replyModelPresets: Flow<List<ReplyModelPreset>>
        get() = flowOf(emptyList())

    suspend fun currentProviderSettings(): ProviderSettings

    suspend fun saveProviderSettings(settings: ProviderSettings)

    suspend fun saveReplyModelPreset(name: String, settings: OpenAiProviderSettings) = Unit

    suspend fun deleteReplyModelPreset(id: String) = Unit
}
