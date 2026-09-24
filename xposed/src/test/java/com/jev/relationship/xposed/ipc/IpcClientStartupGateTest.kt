package com.jev.relationship.xposed.ipc

import org.junit.Assert.assertEquals
import org.junit.Test

class IpcClientStartupGateTest {
    @Test
    fun `starts the ipc connection once`() {
        var attempts = 0
        val gate = IpcClientStartupGate { attempts += 1 }

        gate.start()
        gate.start()

        assertEquals(1, attempts)
    }
}
