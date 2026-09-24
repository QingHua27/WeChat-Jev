package com.jev.relationship.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreXposedIntegrationRepository(
    private val context: Context,
    private val secretProtector: SecretProtector,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore,
) : XposedIntegrationRepository {
    override val settings: Flow<XposedIntegrationSettings> = flow {
        migrateLegacyPairing()
        emitAll(dataStore.data.map { preferences ->
            XposedIntegrationSettings(
                enabled = preferences[Keys.enabled] ?: false,
                pairingToken = preferences[Keys.pairingToken]
                    ?.let { encrypted -> runCatching { secretProtector.decrypt(encrypted) }.getOrNull() }
                    ?.takeIf { it.isNotBlank() },
            )
        })
    }

    override suspend fun current(): XposedIntegrationSettings = settings.first()

    override suspend fun activateWithPairingToken(token: String) {
        require(token.isNotBlank()) { "配对令牌不能为空" }
        dataStore.edit { preferences ->
            preferences[Keys.pairingToken] = secretProtector.encrypt(token)
            preferences[Keys.enabled] = true
            preferences[Keys.pairingVersion] = CURRENT_PAIRING_VERSION
        }
    }

    override suspend fun disable() {
        dataStore.edit { preferences ->
            preferences[Keys.enabled] = false
            preferences.remove(Keys.pairingToken)
            preferences[Keys.pairingVersion] = CURRENT_PAIRING_VERSION
        }
    }

    private suspend fun migrateLegacyPairing() {
        dataStore.edit { preferences ->
            if ((preferences[Keys.pairingVersion] ?: 0) < CURRENT_PAIRING_VERSION) {
                // The old standalone module had a different LSPosed preference store,
                // so its host-side token cannot prove that the merged module is paired.
                preferences[Keys.enabled] = false
                preferences.remove(Keys.pairingToken)
                preferences[Keys.pairingVersion] = CURRENT_PAIRING_VERSION
            }
        }
    }

    private object Keys {
        val enabled = booleanPreferencesKey("xposed_integration_enabled")
        val pairingToken = stringPreferencesKey("xposed_pairing_token")
        val pairingVersion = intPreferencesKey("xposed_pairing_version")
    }

    private companion object {
        const val CURRENT_PAIRING_VERSION = 1
    }
}
