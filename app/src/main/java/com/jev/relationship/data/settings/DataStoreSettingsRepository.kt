package com.jev.relationship.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class DataStoreSettingsRepository(
    private val context: Context,
    private val secretProtector: SecretProtector,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore,
) : SettingsRepository {
    override val providerSettings: Flow<ProviderSettings> = dataStore.data.map { preferences ->
        ProviderSettings(
            baseUrl = JevProviderDefaults.BASE_URL,
            apiKey = preferences[Keys.apiKey]?.let(secretProtector::decrypt).orEmpty(),
            model = JevProviderDefaults.MODEL,
            replyBaseUrl = preferences[Keys.replyBaseUrl].orEmpty(),
            replyApiKey = preferences[Keys.replyApiKey]?.let(secretProtector::decrypt).orEmpty(),
            replyModel = preferences[Keys.replyModel].orEmpty(),
        )
    }

    override val replyModelPresets: Flow<List<ReplyModelPreset>> = dataStore.data.map { preferences ->
        preferences[Keys.replyPresetIds].orEmpty().mapNotNull { id ->
            val name = preferences[Keys.replyPresetName(id)] ?: return@mapNotNull null
            val baseUrl = preferences[Keys.replyPresetBaseUrl(id)] ?: return@mapNotNull null
            val model = preferences[Keys.replyPresetModel(id)] ?: return@mapNotNull null
            val apiKey = preferences[Keys.replyPresetApiKey(id)]
                ?.let { encrypted -> runCatching { secretProtector.decrypt(encrypted) }.getOrNull() }
                ?: return@mapNotNull null
            ReplyModelPreset(id, name, OpenAiProviderSettings(baseUrl, apiKey, model))
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun currentProviderSettings(): ProviderSettings = providerSettings.first()

    override suspend fun saveProviderSettings(settings: ProviderSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.baseUrl] = JevProviderDefaults.BASE_URL
            preferences[Keys.apiKey] = secretProtector.encrypt(settings.apiKey.trim())
            preferences[Keys.model] = JevProviderDefaults.MODEL
            preferences[Keys.replyBaseUrl] = settings.replyBaseUrl.trim()
            preferences[Keys.replyApiKey] = secretProtector.encrypt(settings.replyApiKey.trim())
            preferences[Keys.replyModel] = settings.replyModel.trim()
        }
    }

    override suspend fun saveReplyModelPreset(name: String, settings: OpenAiProviderSettings) {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "预设名称不能为空" }
        require(settings.isConfigured) { "请先填写完整的理解模型配置" }
        val normalizedSettings = settings.copy(
            baseUrl = settings.normalizedBaseUrl(),
            apiKey = settings.apiKey.trim(),
            model = settings.model.trim(),
        )
        dataStore.edit { preferences ->
            val ids = preferences[Keys.replyPresetIds].orEmpty()
            val existingId = ids.firstOrNull { id ->
                preferences[Keys.replyPresetName(id)].equals(normalizedName, ignoreCase = true)
            }
            val id = existingId ?: UUID.randomUUID().toString()
            preferences[Keys.replyPresetIds] = ids + id
            preferences[Keys.replyPresetName(id)] = normalizedName
            preferences[Keys.replyPresetBaseUrl(id)] = normalizedSettings.baseUrl
            preferences[Keys.replyPresetApiKey(id)] = secretProtector.encrypt(normalizedSettings.apiKey)
            preferences[Keys.replyPresetModel(id)] = normalizedSettings.model
        }
    }

    override suspend fun deleteReplyModelPreset(id: String) {
        dataStore.edit { preferences ->
            preferences[Keys.replyPresetIds] = preferences[Keys.replyPresetIds].orEmpty() - id
            preferences.remove(Keys.replyPresetName(id))
            preferences.remove(Keys.replyPresetBaseUrl(id))
            preferences.remove(Keys.replyPresetApiKey(id))
            preferences.remove(Keys.replyPresetModel(id))
        }
    }

    private object Keys {
        val baseUrl = stringPreferencesKey("provider_base_url")
        val apiKey = stringPreferencesKey("provider_api_key")
        val model = stringPreferencesKey("provider_model")
        val replyBaseUrl = stringPreferencesKey("reply_provider_base_url")
        val replyApiKey = stringPreferencesKey("reply_provider_api_key")
        val replyModel = stringPreferencesKey("reply_provider_model")
        val replyPresetIds = stringSetPreferencesKey("reply_model_preset_ids")
        fun replyPresetName(id: String) = stringPreferencesKey("reply_model_preset_${id}_name")
        fun replyPresetBaseUrl(id: String) = stringPreferencesKey("reply_model_preset_${id}_base_url")
        fun replyPresetApiKey(id: String) = stringPreferencesKey("reply_model_preset_${id}_api_key")
        fun replyPresetModel(id: String) = stringPreferencesKey("reply_model_preset_${id}_model")
    }
}
