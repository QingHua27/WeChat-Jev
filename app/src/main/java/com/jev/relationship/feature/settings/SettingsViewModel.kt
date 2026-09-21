package com.jev.relationship.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.data.settings.XposedIntegrationRepository
import com.jev.relationship.data.settings.XposedIntegrationSettings
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
    val xposed: XposedIntegrationSettings = XposedIntegrationSettings(),
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

    init {
        viewModelScope.launch {
            repository.providerSettings.collectLatest { settings ->
                _uiState.update { it.copy(settings = settings, error = null) }
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

    fun updateBaseUrl(value: String) = updateSettings { it.copy(settings = it.settings.copy(baseUrl = value), saved = false) }

    fun updateApiKey(value: String) = updateSettings { it.copy(settings = it.settings.copy(apiKey = value), saved = false) }

    fun updateModel(value: String) = updateSettings { it.copy(settings = it.settings.copy(model = value), saved = false) }

    fun updateReplyBaseUrl(value: String) = updateSettings {
        it.copy(settings = it.settings.copy(replyBaseUrl = value), saved = false)
    }

    fun updateReplyApiKey(value: String) = updateSettings {
        it.copy(settings = it.settings.copy(replyApiKey = value), saved = false)
    }

    fun updateReplyModel(value: String) = updateSettings {
        it.copy(settings = it.settings.copy(replyModel = value), saved = false)
    }

    fun enableXposedIntegration() {
        viewModelScope.launch {
            xposedRepository.rotatePairingToken()
        }
    }

    fun rotateXposedPairingToken() {
        viewModelScope.launch {
            xposedRepository.rotatePairingToken()
        }
    }

    fun disableXposedIntegration() {
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

    fun save() {
        val settings = _uiState.value.settings
        if (settings.baseUrl.isNotBlank()) {
            runCatching { settings.normalizedBaseUrl() }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "URL 无效") }
                    return
                }
        }
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
                    baseUrl = settings.baseUrl.trim(),
                    apiKey = settings.apiKey.trim(),
                    model = settings.model.trim().ifEmpty { "jev-latest" },
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
