package com.jev.relationship.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettings
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
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

        viewModel.updateBaseUrl(" https://api.example.com/v1 ")
        viewModel.updateApiKey(" secret ")
        viewModel.updateModel(" model-x ")
        viewModel.save()
        advanceUntilIdle()

        assertEquals("https://api.example.com/v1", repository.saved.baseUrl)
        assertEquals("secret", repository.saved.apiKey)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun enablingXposedIntegrationExposesPairingToken() = runTest {
        val xposedRepository = RecordingXposedRepository()
        val viewModel = SettingsViewModel(
            RecordingSettingsRepository(),
            xposedRepository,
            RecordingRealtimeRepository(),
            SavedStateHandle(),
        )

        viewModel.enableXposedIntegration()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.xposed.enabled)
        assertEquals("generated-token", viewModel.uiState.value.xposed.pairingToken)
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

    private class RecordingSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(ProviderSettings())
        var saved = ProviderSettings()

        override val providerSettings: Flow<ProviderSettings> = state

        override suspend fun currentProviderSettings(): ProviderSettings = state.value

        override suspend fun saveProviderSettings(settings: ProviderSettings) {
            saved = settings
            state.value = settings
        }
    }

    private class RecordingXposedRepository(
        initial: XposedIntegrationSettings = XposedIntegrationSettings(),
    ) : XposedIntegrationRepository {
        private val state = MutableStateFlow(initial)

        override val settings: Flow<XposedIntegrationSettings> = state

        override suspend fun current(): XposedIntegrationSettings = state.value

        override suspend fun setEnabled(enabled: Boolean) {
            state.value = state.value.copy(enabled = enabled)
        }

        override suspend fun rotatePairingToken(): String {
            state.value = XposedIntegrationSettings(enabled = true, pairingToken = "generated-token")
            return "generated-token"
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
