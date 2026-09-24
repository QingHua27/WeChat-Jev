package com.jev.relationship

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `settings is the only main application destination`() {
        assertEquals(listOf(AppDestination.Settings), AppDestination.entries)
    }
}
