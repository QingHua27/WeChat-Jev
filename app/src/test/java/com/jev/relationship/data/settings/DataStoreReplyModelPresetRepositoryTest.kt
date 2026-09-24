package com.jev.relationship.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataStoreReplyModelPresetRepositoryTest {
    @Test
    fun `presets persist encrypted keys and replace by name`() = runBlocking {
        val store = InMemoryDataStore()
        val repository = DataStoreSettingsRepository(
            context = RuntimeEnvironment.getApplication(),
            secretProtector = TestSecretProtector,
            dataStore = store,
        )
        val first = OpenAiProviderSettings(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey = "secret-one",
            model = "qwen3.8-flash",
        )

        repository.saveReplyModelPreset("百炼", first)
        val initialPreset = repository.replyModelPresets.first().single()

        assertEquals("百炼", initialPreset.name)
        assertEquals(first.copy(baseUrl = first.normalizedBaseUrl()), initialPreset.settings)
        assertTrue(store.data.first().asMap().values.none { it == "secret-one" })

        val replacement = first.copy(apiKey = "secret-two", model = "qwen3.8-plus")
        repository.saveReplyModelPreset(" 百炼 ", replacement)
        val replacedPresets = repository.replyModelPresets.first()

        assertEquals(1, replacedPresets.size)
        assertEquals(initialPreset.id, replacedPresets.single().id)
        assertEquals(replacement.copy(baseUrl = replacement.normalizedBaseUrl()), replacedPresets.single().settings)
        assertTrue(store.data.first().asMap().values.contains("encrypted:secret-two"))

        repository.deleteReplyModelPreset(initialPreset.id)
        assertTrue(repository.replyModelPresets.first().isEmpty())
    }

    @Test
    fun `blank preset name is rejected`() = runBlocking {
        val repository = DataStoreSettingsRepository(
            context = RuntimeEnvironment.getApplication(),
            secretProtector = TestSecretProtector,
            dataStore = InMemoryDataStore(),
        )

        val failure = runCatching {
            repository.saveReplyModelPreset("  ", OpenAiProviderSettings("url", "key", "model"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private class InMemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val values = MutableStateFlow(initial)
        override val data: Flow<Preferences> = values

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }

    private object TestSecretProtector : SecretProtector {
        override fun encrypt(value: String): String = "encrypted:$value"
        override fun decrypt(value: String): String = value.removePrefix("encrypted:")
    }
}
