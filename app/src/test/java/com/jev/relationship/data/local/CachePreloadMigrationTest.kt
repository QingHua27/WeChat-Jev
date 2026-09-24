package com.jev.relationship.data.local

import androidx.room.Room
import com.jev.relationship.ipc.IpcAnalysisResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CachePreloadMigrationTest {
    @Test fun `version four cache is indexed without deleting cache or saved conversation`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "preload-migration-${System.nanoTime()}.db"
        val original = Room.databaseBuilder(context, JevDatabase::class.java, name).allowMainThreadQueries().build()
        val expected = IpcAnalysisResult(messageId = "wechat-8.0.72-42", conversationHash = "chat",
            textHash = "text", isOutgoing = false, emotion = "平静", intents = emptyList(),
            riskLevel = 0, suggestion = "", detailIntention = "旧解读", detailContextual = true)
        RoomChatAssistantStore(original).saveReport("chat", "联系人", "问题", "已保存回答")
        original.analysisResultCacheDao().save(AnalysisResultCacheEntity.from(42, expected))
        original.openHelper.writableDatabase.apply {
            execSQL("ALTER TABLE message_analysis_cache RENAME TO old_cache")
            execSQL("CREATE TABLE message_analysis_cache (localMessageId INTEGER NOT NULL PRIMARY KEY, resultJson TEXT NOT NULL, cachedAt INTEGER NOT NULL)")
            execSQL("INSERT INTO message_analysis_cache SELECT localMessageId, resultJson, cachedAt FROM old_cache")
            execSQL("INSERT INTO message_analysis_cache VALUES (43, 'broken json', 1)")
            execSQL("DROP TABLE old_cache")
            execSQL("PRAGMA user_version = 4")
        }
        original.close()
        val migrated = Room.databaseBuilder(context, JevDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(JevDatabaseMigrations.VERSION_4_TO_5).build()
        try {
            val results = mutableListOf<IpcAnalysisResult>()
            RoomAnalysisResultCache(migrated.analysisResultCacheDao()).conversationResults("chat").collect { results.addAll(it) }
            assertEquals(listOf(expected), results)
            assertNotNull(migrated.analysisResultCacheDao().findByMessageId(43))
            assertEquals("已保存回答", RoomChatAssistantStore(migrated).load("chat")?.turns?.last()?.content)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
