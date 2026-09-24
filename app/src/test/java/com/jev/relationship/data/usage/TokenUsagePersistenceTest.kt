package com.jev.relationship.data.usage

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TokenUsagePersistenceTest {
    @Test fun `usage survives restart and duplicate request ids cannot double count`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "token-usage-test.db"
        fun open() = Room.databaseBuilder(context, TokenUsageDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            val event = TokenUsageEvent(id = "same-request", timestampMs = 500, source = "JEV", model = "jev", inputTokens = 900, outputTokens = 100, totalTokens = 1000, succeeded = true)
            db.usageDao().insert(event)
            db.usageDao().insert(event)
            db.close()
            db = open()
            assertEquals(listOf(event), db.usageDao().observe(0, 1000).first())
            assertTrue(db.usageDao().observe(501, 1000).first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
