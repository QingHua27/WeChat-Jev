package com.jev.relationship.data.settings

import android.content.Context
import com.jev.relationship.xposed.XposedModulePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AutomaticXposedConnectionTest {
    private class Repository(initial: XposedIntegrationSettings = XposedIntegrationSettings()) : XposedIntegrationRepository {
        override val settings = MutableStateFlow(initial)
        var writes = 0
        override suspend fun current() = settings.value
        override suspend fun activateWithPairingToken(token: String) {
            writes++
            settings.value = XposedIntegrationSettings(true, token)
        }
        override suspend fun disable() { settings.value = XposedIntegrationSettings() }
    }

    @Test fun `unavailable framework does not enable integration`() = runBlocking {
        val repo = Repository()
        assertFalse(AutomaticXposedConnection(repo, { null }).connect())
        assertFalse(repo.current().enabled)
        assertEquals(0, repo.writes)
    }

    @Test fun `first load provisions both sides and repeated load reuses credentials`() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("auto-first", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repo = Repository()
        val connection = AutomaticXposedConnection(repo, { prefs }, { "new-token" })
        assertTrue(connection.connect())
        assertTrue(connection.connect())
        assertTrue(repo.current().enabled)
        assertEquals("new-token", prefs.getString(XposedModulePreferences.PAIRING_TOKEN, null))
        assertEquals(1, repo.writes)
    }

    @Test fun `existing host credential repairs stale remote without rotating it`() = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("auto-repair", Context.MODE_PRIVATE)
        prefs.edit().putString(XposedModulePreferences.PAIRING_TOKEN, "stale").commit()
        val repo = Repository(XposedIntegrationSettings(true, "existing-token"))
        assertTrue(AutomaticXposedConnection(repo, { prefs }, { error("must reuse") }).connect())
        assertEquals("existing-token", prefs.getString(XposedModulePreferences.PAIRING_TOKEN, null))
        assertEquals(0, repo.writes)
    }
}
