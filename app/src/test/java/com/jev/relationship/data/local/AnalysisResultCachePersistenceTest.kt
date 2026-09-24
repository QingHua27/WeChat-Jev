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
    @Test fun `conversation preload includes older cached messages and excludes other chats`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val cache = RoomAnalysisResultCache(db.analysisResultCacheDao())
            for (id in 1..301) cache.save("wechat-8.0.72-$id", cachedResult().copy(messageId = "wechat-8.0.72-$id"))
            cache.save("wechat-8.0.72-999", cachedResult().copy(messageId = "wechat-8.0.72-999", conversationHash = "other"))
            val loaded = mutableListOf<IpcAnalysisResult>()
            cache.conversationResults("chat").collect { loaded.addAll(it) }
            assertEquals(301, loaded.size)
            assertEquals(301, loaded.map { it.messageId }.distinct().size)
            assertEquals(true, loaded.all { it.conversationHash == "chat" })
        } finally { db.close() }
    }
    @Test fun `batch cache preserves legacy validity rules and isolates corrupt entries`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.analysisDao().insert(AnalysisEntity(83, "x".repeat(5000), "平静", "[]", 0, "", "[]", 1L))
            database.analysisDao().insert(AnalysisEntity(84, "short", "平静", "[]", 0, "", "[]", 1L))
            val dao = database.analysisResultCacheDao()
            val valid = cachedResult()
            val oldLong = valid.copy(messageId = "wechat-8.0.72-43", historyId = 83)
            val oldShort = valid.copy(messageId = "wechat-8.0.72-44", historyId = 84)
            val failed = valid.copy(messageId = "wechat-8.0.72-45", detailContextual = false,
                detailIntention = "理解模型已配置，但本次调用失败")
            dao.save(AnalysisResultCacheEntity.from(42, valid))
            dao.save(AnalysisResultCacheEntity(43, Gson().toJson(oldLong), 1L))
            dao.save(AnalysisResultCacheEntity(44, Gson().toJson(oldShort), 1L))
            dao.save(AnalysisResultCacheEntity.from(45, failed))
            dao.save(AnalysisResultCacheEntity(46, "invalid json", 1L))
            val found = RoomAnalysisResultCache(dao).findAll((42..46).map { "wechat-8.0.72-$it" })
            assertEquals(mapOf(valid.messageId to valid, oldShort.messageId to oldShort), found)
        } finally { database.close() }
    }

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
