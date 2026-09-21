package com.jev.relationship.data.remote

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.JevAnalyzer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigurableJevAnalyzerTest {
    @Test
    fun `unconfigured settings use local fallback`() = runTest {
        val fallback = RecordingAnalyzer("local")
        val remote = RecordingAnalyzer("remote")
        val analyzer = ConfigurableJevAnalyzer(
            settingsRepository = SettingsRepositoryStub(ProviderSettings()),
            remote = remote,
            fallback = fallback,
        )

        val result = analyzer.analyze(Conversation("hello"))

        assertEquals("local", result.emotion)
        assertEquals(1, fallback.calls)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `configured settings use remote analyzer`() = runTest {
        val fallback = RecordingAnalyzer("local")
        val remote = RecordingAnalyzer("remote")
        val analyzer = ConfigurableJevAnalyzer(
            settingsRepository = SettingsRepositoryStub(
                ProviderSettings(baseUrl = "https://api.example.com", apiKey = "key"),
            ),
            remote = remote,
            fallback = fallback,
        )

        val result = analyzer.analyze(Conversation("hello"))

        assertEquals("remote", result.emotion)
        assertEquals(1, remote.calls)
        assertEquals(0, fallback.calls)
    }

    private class RecordingAnalyzer(private val emotion: String) : JevAnalyzer {
        var calls = 0

        override suspend fun analyze(conversation: Conversation): AnalysisResult {
            calls += 1
            return AnalysisResult(emotion, emptyList(), 0, "local suggestion")
        }
    }

    private class SettingsRepositoryStub(
        private val value: ProviderSettings,
    ) : SettingsRepository {
        override val providerSettings: Flow<ProviderSettings> = emptyFlow()

        override suspend fun currentProviderSettings(): ProviderSettings = value

        override suspend fun saveProviderSettings(settings: ProviderSettings) = Unit
    }
}
