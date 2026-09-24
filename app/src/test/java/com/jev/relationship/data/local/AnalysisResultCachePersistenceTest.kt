package com.jev.relationship.data.local

import androidx.room.Room
import com.google.gson.Gson
import com.jev.relationship.ipc.IpcAnalysisResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AnalysisResultCachePersistenceTest {
    @Test
    fun `legacy long history interpretation is invalidated and regenerated result stays cached`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, JevDatabase::class.java).allowMainThreadQueries().build()
        try {
            // Reproduces the old first-4000-character prompt: the real target was cut off.
            val conversation = "对方：旧聊天\n".repeat(600) + "我：我去洗澡啦\n" +
                "对方：近期聊天\n".repeat(300) + "\n当前待分析消息：我才起来"
            database.analysisDao().insert(AnalysisEntity(83, conversation, "平静", "[]", 0, "", "[]", 1L))
            val wrong = cachedResult().copy(historyId = 83, detailIntention = "结束话题去洗澡。")
            val dao = database.analysisResultCacheDao()
            // Raw JSON has no provenance marker, just like the installed legacy cache.
            dao.save(AnalysisResultCacheEntity(42, Gson().toJson(wrong), 1L))
            val cache = RoomAnalysisResultCache(dao)
            assertNull(cache.find(wrong.messageId))
            val corrected = wrong.copy(detailIntention = "告知自己刚起床。")
            cache.save(corrected.messageId, corrected)
            assertEquals(corrected, RoomAnalysisResultCache(dao).find(corrected.messageId))
        } finally { database.close() }
    }

    @Test
    fun `legacy short context remains cached without another model call`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.analysisDao().insert(AnalysisEntity(84, "对方：我才起来\n当前待分析消息：我才起来", "平静", "[]", 0, "", "[]", 1L))
            val expected = cachedResult().copy(historyId = 84)
            database.analysisResultCacheDao().save(AnalysisResultCacheEntity(42, Gson().toJson(expected), 1L))
            assertEquals(expected, RoomAnalysisResultCache(database.analysisResultCacheDao()).find(expected.messageId))
        } finally { database.close() }
    }

    private fun cachedResult() = IpcAnalysisResult(
        messageId = "wechat-8.0.72-42", conversationHash = "chat", textHash = "message",
        isOutgoing = false, emotion = "平静", intents = emptyList(), riskLevel = 1,
        suggestion = "", detailIntention = "告知自己刚起床。", detailContextual = true,
    )

    @Test
    fun `successful bubble survives database close and reopen without being overwritten by a failure`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "bubble-cache-reopen.db"
        fun open() = Room.databaseBuilder(context, JevDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        var database = open()
        val expected = IpcAnalysisResult(
            messageId = "wechat-8.0.72-42", conversationHash = "chat", textHash = "message",
            isOutgoing = false, emotion = "平静", intents = emptyList(), riskLevel = 1,
            suggestion = "确认时间", detailIntention = "对方在确认见面时间。", detailContextual = true,
        )
        try {
            RoomAnalysisResultCache(database.analysisResultCacheDao()).save(expected.messageId, expected)
            database.close()
            database = open()
            val reopened = RoomAnalysisResultCache(database.analysisResultCacheDao())
            assertEquals(expected, reopened.find(expected.messageId))
            reopened.save(expected.messageId, expected.copy(detailContextual = false, detailIntention = ""))
            assertEquals(expected, reopened.find("wechat-local-42"))
            assertNull(reopened.find("wechat-8.0.72-43"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
