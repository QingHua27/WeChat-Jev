package com.jev.relationship.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.JevProviderDefaults
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.data.settings.OpenAiProviderSettings
import com.jev.relationship.data.settings.ReplyModelPreset
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.data.settings.XposedIntegrationRepository
import com.jev.relationship.data.settings.XposedIntegrationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun savePersistsTrimmedProviderSettings() = runTest {
        val repository = RecordingSettingsRepository()
        val viewModel = SettingsViewModel(
            repository,
            RecordingXposedRepository(),
            RecordingRealtimeRepository(),
            SavedStateHandle(),
        )

        viewModel.updateApiKey(" secret ")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(JevProviderDefaults.BASE_URL, repository.saved.baseUrl)
        assertEquals("secret", repository.saved.apiKey)
        assertEquals(JevProviderDefaults.MODEL, repository.saved.model)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun selectingReplyPresetImmediatelyPersistsItAsTheActiveConfiguration() = runTest {
        val preset = ReplyModelPreset(
            id = "preset-1",
            name = "百炼 Flash",
            settings = OpenAiProviderSettings("https://dashscope.aliyuncs.com/compatible-mode/v1/", "key", "qwen3.8-flash"),
        )
        val repository = RecordingSettingsRepository(presets = listOf(preset))
        val viewModel = SettingsViewModel(
            repository,
            RecordingXposedRepository(),
            RecordingRealtimeRepository(),
            SavedStateHandle(),
        )

        viewModel.selectReplyModelPreset(preset.id)
        advanceUntilIdle()

        assertEquals(preset.settings, repository.saved.replySettings())
        assertEquals("qwen3.8-flash", viewModel.uiState.value.settings.replyModel)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun pairingIsNotPersistedUntilRemoteProvisioningSucceeds() = runTest {
        val xposedRepository = RecordingXposedRepository()
        val viewModel = SettingsViewModel(
            RecordingSettingsRepository(),
            xposedRepository,
            RecordingRealtimeRepository(),
            SavedStateHandle(),
        )

        val token = viewModel.beginXposedPairing()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pairingInProgress)
        assertFalse(viewModel.uiState.value.xposed.enabled)
        assertNull(xposedRepository.savedToken)

        viewModel.completeXposedPairing(token)
        advanceUntilIdle()

        assertEquals(token, xposedRepository.savedToken)
        assertTrue(viewModel.uiState.value.xposed.enabled)
        assertFalse(viewModel.uiState.value.pairingInProgress)
        assertNull(viewModel.uiState.value.pairingError)
    }

    @Test
    fun pairingFailureLeavesIntegrationDisabledAndRetryStartsFreshPairing() = runTest {
        val xposedRepository = RecordingXposedRepository()
        val viewModel = SettingsViewModel(
            RecordingSettingsRepository(),
            xposedRepository,
            RecordingRealtimeRepository(),
            SavedStateHandle(),
        )

        viewModel.beginXposedPairing()
        viewModel.failXposedPairing("LSPosed service unavailable")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.xposed.enabled)
        assertFalse(viewModel.uiState.value.pairingInProgress)
        assertEquals("LSPosed service unavailable", viewModel.uiState.value.pairingError)
        assertNull(xposedRepository.savedToken)

        val retryToken = viewModel.beginXposedPairing()
        assertFalse(retryToken.isBlank())
        assertTrue(viewModel.uiState.value.pairingInProgress)
        assertNull(viewModel.uiState.value.pairingError)
    }

    @Test
    fun disablingXposedIntegrationClearsPairingToken() = runTest {
        val xposedRepository = RecordingXposedRepository(
            XposedIntegrationSettings(enabled = true, pairingToken = "old-token"),
        )
        val viewModel = SettingsViewModel(
            RecordingSettingsRepository(),
            xposedRepository,
            RecordingRealtimeRepository(),
            SavedStateHandle(),
        )

        viewModel.disableXposedIntegration()
        advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.xposed.enabled)
        assertEquals(null, viewModel.uiState.value.xposed.pairingToken)
    }

    @Test
    fun realtimeEnableRequiresConsentAndCancelKeepsDefaultOff() = runTest {
        val realtime = RecordingRealtimeRepository()
        val viewModel = SettingsViewModel(
            RecordingSettingsRepository(),
            RecordingXposedRepository(),
            realtime,
            SavedStateHandle(),
        )
        advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.realtime.enabled)
        viewModel.requestEnableRealtime()
        assertTrue(viewModel.uiState.value.showRealtimeConsent)

        viewModel.cancelEnableRealtime()
        assertTrue(!viewModel.uiState.value.showRealtimeConsent)
        assertTrue(!realtime.enabled)
    }

    @Test
    fun confirmingRealtimeConsentPersistsEnableAndDisablePersistsFalse() = runTest {
        val realtime = RecordingRealtimeRepository()
        val viewModel = SettingsViewModel(
            RecordingSettingsRepository(),
            RecordingXposedRepository(),
            realtime,
            SavedStateHandle(),
        )

        viewModel.requestEnableRealtime()
        viewModel.confirmEnableRealtime()
        advanceUntilIdle()
        assertTrue(realtime.enabled)
        assertTrue(viewModel.uiState.value.realtime.enabled)

        viewModel.disableRealtime()
        advanceUntilIdle()
        assertTrue(!realtime.enabled)
        assertTrue(!viewModel.uiState.value.realtime.enabled)
    }

    private class RecordingSettingsRepository(
        presets: List<ReplyModelPreset> = emptyList(),
    ) : SettingsRepository {
        private val state = MutableStateFlow(ProviderSettings())
        private val presetsState = MutableStateFlow(presets)
        var saved = ProviderSettings()

        override val providerSettings: Flow<ProviderSettings> = state
        override val replyModelPresets: Flow<List<ReplyModelPreset>> = presetsState

        override suspend fun currentProviderSettings(): ProviderSettings = state.value

        override suspend fun saveProviderSettings(settings: ProviderSettings) {
            saved = settings
            state.value = settings
        }

        override suspend fun saveReplyModelPreset(name: String, settings: OpenAiProviderSettings) {
            val existing = presetsState.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            val preset = ReplyModelPreset(existing?.id ?: "preset-${presetsState.value.size + 1}", name.trim(), settings)
            presetsState.value = (presetsState.value.filterNot { it.id == preset.id } + preset)
        }

        override suspend fun deleteReplyModelPreset(id: String) {
            presetsState.value = presetsState.value.filterNot { it.id == id }
        }
    }

    private class RecordingXposedRepository(
        initial: XposedIntegrationSettings = XposedIntegrationSettings(),
    ) : XposedIntegrationRepository {
        private val state = MutableStateFlow(initial)
        var savedToken: String? = null

        override val settings: Flow<XposedIntegrationSettings> = state

        override suspend fun current(): XposedIntegrationSettings = state.value

        override suspend fun activateWithPairingToken(token: String) {
            savedToken = token
            state.value = XposedIntegrationSettings(enabled = true, pairingToken = token)
        }

        override suspend fun disable() {
            state.value = XposedIntegrationSettings()
        }
    }

    private class RecordingRealtimeRepository(
        initial: RealtimeAssistantSettings = RealtimeAssistantSettings(),
    ) : RealtimeAssistantSettingsRepository {
        private val state = MutableStateFlow(initial)
        var enabled: Boolean = initial.enabled

        override val settings: Flow<RealtimeAssistantSettings> = state

        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
            state.value = RealtimeAssistantSettings(enabled)
        }
    }
}
