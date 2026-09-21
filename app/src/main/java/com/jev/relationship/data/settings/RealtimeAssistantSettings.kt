package com.jev.relationship.data.settings

import kotlinx.coroutines.flow.Flow

data class RealtimeAssistantSettings(
    val enabled: Boolean = false,
)

interface RealtimeAssistantSettingsRepository {
    val settings: Flow<RealtimeAssistantSettings>

    suspend fun setEnabled(enabled: Boolean)
}
