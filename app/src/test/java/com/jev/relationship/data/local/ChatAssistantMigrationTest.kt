package com.jev.relationship.data.local

import androidx.room.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatAssistantMigrationTest {
    @Test
    fun `version three history survives migration and assistant tables become usable`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "assistant-migration-${System.nanoTime()}.db"
        try {
            val original = Room.databaseBuilder(context, JevDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            val analysisId = kotlinx.coroutines.runBlocking {
                original.analysisDao().insert(AnalysisEntity(
                    conversationText = "对方：周五见吗",
                    emotion = "平静",
                    intentsJson = "[]",
                    riskLevel = 1,
                    suggestion = "确认时间",
                    repliesJson = "[]",
                    createdAt = 123L,
                ))
            }
            original.openHelper.writableDatabase.apply {
                execSQL("DROP TABLE chat_assistant_turns")
                execSQL("DROP TABLE chat_assistant_sessions")
                execSQL("PRAGMA user_version = 3")
            }
            original.close()

            val migrated = Room.databaseBuilder(context, JevDatabase::class.java, name)
                .allowMainThreadQueries()
                .addMigrations(JevDatabaseMigrations.VERSION_3_TO_4)
                .build()
            try {
                assertNotNull(kotlinx.coroutines.runBlocking { migrated.analysisDao().findById(analysisId) })
                val store = RoomChatAssistantStore(migrated)
                kotlinx.coroutines.runBlocking { store.saveReport("alice", "Alice", "请根据最新聊天记录生成整体分析。", "她想确认时间。") }
                assertEquals("她想确认时间。", kotlinx.coroutines.runBlocking {
                    store.load("alice")?.turns?.lastOrNull()?.content
                })
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
