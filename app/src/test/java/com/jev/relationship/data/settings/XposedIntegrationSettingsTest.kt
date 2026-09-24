package com.jev.relationship.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedIntegrationSettingsTest {
    @Test
    fun settingsAreDisabledAndUnpairedByDefault() {
        val settings = XposedIntegrationSettings()

        assertFalse(settings.enabled)
        assertFalse(settings.isPaired)
    }

    @Test
    fun generatedPairingTokensAreNonEmptyAndUnique() {
        val first = XposedPairingTokenGenerator.generate()
        val second = XposedPairingTokenGenerator.generate()

        assertTrue(first.length >= XposedPairingTokenGenerator.MIN_LENGTH)
        assertTrue(second.length >= XposedPairingTokenGenerator.MIN_LENGTH)
        assertNotEquals(first, second)
    }

    @Test
    fun disablingSettingsRemovesTheActiveToken() {
        val settings = XposedIntegrationSettings(enabled = true, pairingToken = "token")

        val disabled = settings.disabled()

        assertEquals(XposedIntegrationSettings(), disabled)
    }
}
