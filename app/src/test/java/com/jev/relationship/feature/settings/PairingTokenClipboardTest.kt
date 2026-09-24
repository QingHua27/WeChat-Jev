package com.jev.relationship.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingTokenClipboardTest {
    @Test
    fun `normalizes token into one-line clipboard text`() {
        assertEquals(
            "token-value",
            PairingTokenClipboard.normalize(" \n token-\nvalue \t"),
        )
    }
}
