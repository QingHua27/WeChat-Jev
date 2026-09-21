package com.jev.relationship

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.jev.relationship.feature.analyze.AnalysisResultContent
import com.jev.relationship.feature.analyze.AnalyzeScreen
import com.jev.relationship.feature.analyze.AnalyzeViewModel
import com.jev.relationship.feature.history.HistoryScreen
import com.jev.relationship.feature.history.HistoryViewModel
import com.jev.relationship.feature.contacts.ContactMemoryScreen
import com.jev.relationship.feature.contacts.ContactMemoryViewModel
import com.jev.relationship.feature.settings.SettingsScreen
import com.jev.relationship.feature.settings.SettingsViewModel
import com.jev.relationship.feature.settings.PairingTokenClipboard
import com.jev.relationship.service.FloatingAssistantService
import com.jev.relationship.ui.theme.JevTheme

private enum class AppDestination {
    Analyze,
    History,
    Settings,
    Contacts,
    HistoryDetail,
}

@Composable
fun JevApp() {
    var destination by remember { mutableStateOf(AppDestination.Analyze) }
    var selectedHistory by remember { mutableStateOf<com.jev.relationship.core.model.SavedAnalysis?>(null) }
    val analyzeViewModel: AnalyzeViewModel = hiltViewModel()
    val historyViewModel: HistoryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val contactMemoryViewModel: ContactMemoryViewModel = hiltViewModel()
    val analyzeState by analyzeViewModel.uiState.collectAsStateWithLifecycle()
    val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val contactMemoryState by contactMemoryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val overlayPermissionGranted = Settings.canDrawOverlays(context)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                analyzeViewModel.onTranscriptChanged(reader.readText())
            }
        }
    }

    when (destination) {
        AppDestination.Analyze -> AnalyzeScreen(
            state = analyzeState,
            onTranscriptChanged = analyzeViewModel::onTranscriptChanged,
            onRequestAnalysis = analyzeViewModel::requestAnalysis,
            onCancelPrivacy = analyzeViewModel::cancelPrivacyConsent,
            onConfirmPrivacy = analyzeViewModel::confirmPrivacyAndAnalyze,
            onRetry = analyzeViewModel::retry,
            onOpenHistory = { destination = AppDestination.History },
            onOpenSettings = { destination = AppDestination.Settings },
            onOpenContacts = { destination = AppDestination.Contacts },
            onImportText = { importLauncher.launch(arrayOf("text/plain", "text/*")) },
            onContactSelected = analyzeViewModel::selectContact,
        )

        AppDestination.History -> HistoryScreen(
            state = historyState,
            onOpen = { item -> selectedHistory = item; destination = AppDestination.HistoryDetail },
            onDelete = historyViewModel::delete,
            onBack = { destination = AppDestination.Analyze },
        )

        AppDestination.Settings -> SettingsScreen(
            state = settingsState,
            onBaseUrlChanged = settingsViewModel::updateBaseUrl,
            onApiKeyChanged = settingsViewModel::updateApiKey,
            onModelChanged = settingsViewModel::updateModel,
            onReplyBaseUrlChanged = settingsViewModel::updateReplyBaseUrl,
            onReplyApiKeyChanged = settingsViewModel::updateReplyApiKey,
            onReplyModelChanged = settingsViewModel::updateReplyModel,
            onEnableXposedIntegration = settingsViewModel::enableXposedIntegration,
            onCopyXposedPairingToken = { token ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Jev Xposed pairing token", PairingTokenClipboard.normalize(token)))
            },
            onRotateXposedPairingToken = settingsViewModel::rotateXposedPairingToken,
            onDisableXposedIntegration = settingsViewModel::disableXposedIntegration,
            onOpenAccessibilitySettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onStartFloatingAssistant = {
                if (Settings.canDrawOverlays(context)) {
                    val intent = Intent(context, FloatingAssistantService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
            onStopFloatingAssistant = {
                context.stopService(Intent(context, FloatingAssistantService::class.java))
            },
            overlayPermissionGranted = overlayPermissionGranted,
            onRequestEnableRealtime = settingsViewModel::requestEnableRealtime,
            onConfirmEnableRealtime = settingsViewModel::confirmEnableRealtime,
            onCancelEnableRealtime = settingsViewModel::cancelEnableRealtime,
            onDisableRealtime = settingsViewModel::disableRealtime,
            onSave = settingsViewModel::save,
            onBack = { destination = AppDestination.Analyze },
        )

        AppDestination.Contacts -> ContactMemoryScreen(
            state = contactMemoryState,
            onContactNameChanged = contactMemoryViewModel::updateContactName,
            onSaveContact = contactMemoryViewModel::saveContact,
            onSelectContact = contactMemoryViewModel::selectContact,
            onObservationTextChanged = contactMemoryViewModel::updateObservationText,
            onObservationKindChanged = contactMemoryViewModel::updateObservationKind,
            onAddObservation = contactMemoryViewModel::addObservation,
            onDeleteObservation = contactMemoryViewModel::deleteObservation,
            onBack = { destination = AppDestination.Analyze },
        )

        AppDestination.HistoryDetail -> selectedHistory?.let { item ->
            AnalysisResultContent(
                output = item.output,
                onRetry = { destination = AppDestination.Analyze },
            )
        } ?: run { destination = AppDestination.History }
    }
}

@Composable
fun JevRoot() {
    JevTheme { JevApp() }
}
