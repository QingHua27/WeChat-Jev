package com.jev.relationship.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreRealtimeAssistantRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RealtimeAssistantSettingsRepository {
    override val settings: Flow<RealtimeAssistantSettings> = dataStore.data.map { preferences ->
        RealtimeAssistantSettings(enabled = preferences[Keys.enabled] ?: false)
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.enabled] = enabled
        }
    }

    private object Keys {
        val enabled = booleanPreferencesKey("realtime_assistant_enabled")
    }
}
