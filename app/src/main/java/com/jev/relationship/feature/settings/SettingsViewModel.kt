package com.jev.relationship.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.JevProviderDefaults
import com.jev.relationship.data.settings.ReplyModelPreset
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.data.settings.XposedIntegrationRepository
import com.jev.relationship.data.settings.XposedIntegrationSettings
import com.jev.relationship.data.settings.XposedPairingTokenGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: ProviderSettings = ProviderSettings(),
    val replyModelPresets: List<ReplyModelPreset> = emptyList(),
    val presetMessage: String? = null,
    val xposed: XposedIntegrationSettings = XposedIntegrationSettings(),
    val pairingInProgress: Boolean = false,
    val pairingError: String? = null,
    val realtime: RealtimeAssistantSettings = RealtimeAssistantSettings(),
    val showRealtimeConsent: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val xposedRepository: XposedIntegrationRepository,
    private val realtimeRepository: RealtimeAssistantSettingsRepository,
    @Suppress("UNUSED_PARAMETER") savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var pendingPairingToken: String? = null

    init {
        viewModelScope.launch {
            repository.providerSettings.collectLatest { settings ->
                _uiState.update { it.copy(settings = settings, error = null) }
            }
        }
        viewModelScope.launch {
            repository.replyModelPresets.collectLatest { presets ->
                _uiState.update { it.copy(replyModelPresets = presets) }
            }
        }
        viewModelScope.launch {
            xposedRepository.settings.collectLatest { xposed ->
                _uiState.update { it.copy(xposed = xposed) }
            }
        }
        viewModelScope.launch {
            realtimeRepository.settings.collectLatest { realtime ->
                _uiState.update { it.copy(realtime = realtime) }
            }
        }
    }

    fun updateApiKey(value: String) = updateSettings { it.copy(settings = it.settings.copy(apiKey = value), saved = false) }

    fun updateReplyBaseUrl(value: String) = updateSettings {
        it.copy(settings = it.settings.copy(replyBaseUrl = value), saved = false)
    }

    fun updateReplyApiKey(value: String) = updateSettings {
        it.copy(settings = it.settings.copy(replyApiKey = value), saved = false)
    }

    fun updateReplyModel(value: String) = updateSettings {
        it.copy(settings = it.settings.copy(replyModel = value), saved = false)
    }

    fun selectReplyModelPreset(id: String) {
        val preset = _uiState.value.replyModelPresets.firstOrNull { it.id == id } ?: return
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    replyBaseUrl = preset.settings.baseUrl,
                    replyApiKey = preset.settings.apiKey,
                    replyModel = preset.settings.model,
                ),
                saved = false,
                presetMessage = "已切换到 ${preset.name}",
            )
        }
        save()
    }

    fun saveReplyModelPreset(name: String) {
        val settings = _uiState.value.settings.replySettings()
        viewModelScope.launch {
            runCatching { repository.saveReplyModelPreset(name, settings) }
                .onSuccess {
                    _uiState.update { state -> state.copy(presetMessage = "已保存预设：${name.trim()}", error = null) }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(error = error.message ?: "保存预设失败", presetMessage = null)
                    }
                }
        }
    }

    fun deleteReplyModelPreset(id: String) {
        viewModelScope.launch {
            runCatching { repository.deleteReplyModelPreset(id) }
                .onSuccess { _uiState.update { it.copy(presetMessage = "已删除模型预设", error = null) } }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "删除预设失败", presetMessage = null) }
                }
        }
    }

    fun beginXposedPairing(): String {
        val token = XposedPairingTokenGenerator.generate()
        pendingPairingToken = token
        _uiState.update { it.copy(pairingInProgress = true, pairingError = null) }
        return token
    }

    fun completeXposedPairing(token: String) {
        if (pendingPairingToken != token) return
        viewModelScope.launch {
            runCatching { xposedRepository.activateWithPairingToken(token) }
                .onSuccess {
                    pendingPairingToken = null
                    _uiState.update { it.copy(pairingInProgress = false, pairingError = null) }
                }
                .onFailure { error ->
                    pendingPairingToken = null
                    _uiState.update {
                        it.copy(
                            pairingInProgress = false,
                            pairingError = error.message ?: "保存配对信息失败，请重试。",
                        )
                    }
                }
        }
    }

    fun failXposedPairing(message: String) {
        pendingPairingToken = null
        _uiState.update {
            it.copy(
                pairingInProgress = false,
                pairingError = message.ifBlank { "LSPosed 配对失败，请重试。" },
            )
        }
    }

    fun disableXposedIntegration() {
        pendingPairingToken = null
        _uiState.update { it.copy(pairingInProgress = false, pairingError = null) }
        viewModelScope.launch {
            xposedRepository.disable()
        }
    }

    fun requestEnableRealtime() {
        _uiState.update { it.copy(showRealtimeConsent = true) }
    }

    fun cancelEnableRealtime() {
        _uiState.update { it.copy(showRealtimeConsent = false) }
    }

    fun confirmEnableRealtime() {
        _uiState.update { it.copy(showRealtimeConsent = false) }
        viewModelScope.launch {
            realtimeRepository.setEnabled(true)
        }
    }

    fun disableRealtime() {
        viewModelScope.launch {
            realtimeRepository.setEnabled(false)
        }
    }

    fun setFastCacheDisplay(enabled: Boolean) {
        viewModelScope.launch { realtimeRepository.setFastCacheDisplay(enabled) }
    }

    fun save() {
        val settings = _uiState.value.settings
        if (settings.replyBaseUrl.isNotBlank()) {
            runCatching { settings.replySettings().normalizedBaseUrl() }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "回复接口 URL 无效") }
                    return
                }
        }
        viewModelScope.launch {
            repository.saveProviderSettings(
                settings.copy(
                    baseUrl = JevProviderDefaults.BASE_URL,
                    apiKey = settings.apiKey.trim(),
                    model = JevProviderDefaults.MODEL,
                    replyBaseUrl = settings.replyBaseUrl.trim(),
                    replyApiKey = settings.replyApiKey.trim(),
                    replyModel = settings.replyModel.trim(),
                ),
            )
            _uiState.update { it.copy(saved = true, error = null) }
        }
    }

    private fun updateSettings(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
    }
}
