package com.jev.relationship.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataStoreXposedIntegrationRepositoryTest {
    @Test
    fun `legacy pairing is cleared once and a new module pairing is retained`() = runBlocking {
        val store = InMemoryDataStore(
            mutablePreferencesOf(
                booleanPreferencesKey("xposed_integration_enabled") to true,
                stringPreferencesKey("xposed_pairing_token") to "encrypted:legacy-token",
            ),
        )
        val repository = DataStoreXposedIntegrationRepository(
            context = RuntimeEnvironment.getApplication(),
            secretProtector = TestSecretProtector,
            dataStore = store,
        )

        val migrated = repository.settings.first()
        assertFalse(migrated.enabled)
        assertNull(migrated.pairingToken)
        assertEquals(false, store.data.first()[booleanPreferencesKey("xposed_integration_enabled")])
        assertNull(store.data.first()[stringPreferencesKey("xposed_pairing_token")])

        repository.activateWithPairingToken("new-module-token")
        val paired = repository.settings.first()
        assertTrue(paired.enabled)
        assertEquals("new-module-token", paired.pairingToken)
    }

    private class InMemoryDataStore(initial: Preferences) : DataStore<Preferences> {
        private val values = MutableStateFlow(initial)
        override val data: Flow<Preferences> = values

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }

    private object TestSecretProtector : SecretProtector {
        override fun encrypt(value: String): String = "encrypted:$value"
        override fun decrypt(value: String): String = value.substringAfter("encrypted:")
    }
}
