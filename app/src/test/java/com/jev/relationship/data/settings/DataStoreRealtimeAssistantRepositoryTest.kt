package com.jev.relationship.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreRealtimeAssistantRepositoryTest {
    @Test
    fun `fresh repository is disabled and enabled state persists`() = runBlocking {
        val repository = DataStoreRealtimeAssistantRepository(InMemoryDataStore())

        assertFalse(repository.settings.first().enabled)

        repository.setEnabled(true)
        assertTrue(repository.settings.first().enabled)

        repository.setEnabled(false)
        assertFalse(repository.settings.first().enabled)
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val values = MutableStateFlow<Preferences>(emptyPreferences())

        override val data = values

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return transform(values.value).also { values.value = it }
        }
    }
}
