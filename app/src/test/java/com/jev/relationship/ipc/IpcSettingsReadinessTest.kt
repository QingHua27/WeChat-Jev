package com.jev.relationship.ipc

import com.jev.relationship.data.settings.XposedIntegrationSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcSettingsReadinessTest {
    @Test
    fun `initial handshake waits until settings have been loaded`() = runTest {
        val readiness = IpcSettingsReadiness()
        val awaiting = async { readiness.awaitInitial() }

        delay(10)
        assertFalse(awaiting.isCompleted)

        readiness.publish(XposedIntegrationSettings(enabled = true, pairingToken = "token"))

        assertTrue(awaiting.await().enabled)
    }
}
