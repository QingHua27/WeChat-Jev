package com.jev.relationship.data.local

import androidx.room.Room
import com.jev.relationship.domain.chatassistant.ChatAssistantStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatAssistantStoreTest {
    @Test
    fun `saved conversation survives closing and reopening the database`() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val databaseName = "assistant-resume-test.db"
        fun open() = Room.databaseBuilder(context, JevDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            RoomChatAssistantStore(database).apply {
                saveReport("alice", "Alice", "分析", "已有分析")
                appendExchange("alice", "Alice", "问题", "回答")
            }
            database.close()
            database = open()
            assertEquals(listOf("分析", "已有分析", "问题", "回答"),
                RoomChatAssistantStore(database).load("alice")!!.turns.map { it.content })
            assertNull(RoomChatAssistantStore(database).load("bob"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `report and successful exchanges persist per conversation across store instances`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            JevDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val store: ChatAssistantStore = RoomChatAssistantStore(database)
            store.saveReport("alice", "Alice", "请根据最新聊天生成整体分析。", "她想得到更明确的安排。")
            store.appendExchange("alice", "Alice", "我怎么回复？", "可以先确认她希望的时间。")
            store.saveReport("room@chatroom", "Friends", "请根据最新聊天生成整体分析。", "群里在协调聚餐。")

            val restored: ChatAssistantStore = RoomChatAssistantStore(database)
            assertEquals(
                listOf("请根据最新聊天生成整体分析。", "她想得到更明确的安排。", "我怎么回复？", "可以先确认她希望的时间。"),
                restored.load("alice")?.turns?.map { it.content },
            )
            assertEquals(listOf("请根据最新聊天生成整体分析。", "群里在协调聚餐。"), restored.load("room@chatroom")?.turns?.map { it.content })
            assertNull(restored.load("unknown"))
        } finally {
            database.close()
        }
    }
}
