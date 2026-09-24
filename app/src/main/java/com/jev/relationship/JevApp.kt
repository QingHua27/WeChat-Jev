package com.jev.relationship

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jev.relationship.feature.settings.SettingsScreen
import com.jev.relationship.feature.settings.SettingsViewModel
import com.jev.relationship.ui.theme.JevTheme

@Composable
fun JevApp() {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    when (AppDestination.Settings) {
        AppDestination.Settings -> SettingsScreen(
            state = settingsState,
            onApiKeyChanged = settingsViewModel::updateApiKey,
            onReplyBaseUrlChanged = settingsViewModel::updateReplyBaseUrl,
            onReplyApiKeyChanged = settingsViewModel::updateReplyApiKey,
            onReplyModelChanged = settingsViewModel::updateReplyModel,
            onRequestEnableRealtime = settingsViewModel::requestEnableRealtime,
            onConfirmEnableRealtime = settingsViewModel::confirmEnableRealtime,
            onCancelEnableRealtime = settingsViewModel::cancelEnableRealtime,
            onDisableRealtime = settingsViewModel::disableRealtime,
            onSave = settingsViewModel::save,
            onSelectPreset = settingsViewModel::selectReplyModelPreset,
            onSavePreset = settingsViewModel::saveReplyModelPreset,
            onDeletePreset = settingsViewModel::deleteReplyModelPreset,
        )
    }
}

@Composable
fun JevRoot() {
    JevTheme { JevApp() }
}
