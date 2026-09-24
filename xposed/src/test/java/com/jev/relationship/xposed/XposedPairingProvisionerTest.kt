package com.jev.relationship.xposed

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class XposedPairingProvisionerTest {
    @Test
    fun `save writes the supplied token to remote preferences`() {
        val preferences = preferences()

        assertTrue(XposedPairingProvisioner.save(preferences, "random-token"))
        assertEquals("random-token", preferences.getString(XposedModulePreferences.PAIRING_TOKEN, null))
    }

    @Test
    fun `save rejects blank token without overwriting existing token`() {
        val preferences = preferences()
        preferences.edit().putString(XposedModulePreferences.PAIRING_TOKEN, "existing-token").commit()

        assertFalse(XposedPairingProvisioner.save(preferences, "  "))
        assertEquals("existing-token", preferences.getString(XposedModulePreferences.PAIRING_TOKEN, null))
    }

    @Test
    fun `clear removes the remote pairing token`() {
        val preferences = preferences()
        preferences.edit().putString(XposedModulePreferences.PAIRING_TOKEN, "existing-token").commit()

        assertTrue(XposedPairingProvisioner.clear(preferences))
        assertEquals(null, preferences.getString(XposedModulePreferences.PAIRING_TOKEN, null))
    }

    private fun preferences() =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing-provisioner-test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
}
