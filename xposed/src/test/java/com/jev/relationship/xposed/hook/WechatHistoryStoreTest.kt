package com.jev.relationship.xposed.hook

import android.database.sqlite.SQLiteDatabase
import com.jev.relationship.ipc.LocalHistoryRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WechatHistoryStoreTest {
    @Test
    fun `read all local text in time order across pages excluding revoked and other chats`() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE message(msgId INTEGER PRIMARY KEY, type INTEGER, isSend INTEGER, createTime INTEGER, talker TEXT, content TEXT)")
            for (id in 1..123) db.execSQL("INSERT INTO message VALUES(?,1,?,?,?,?)", arrayOf(id, id % 2, id * 1000, "friend'quoted", "message-$id"))
            db.execSQL("UPDATE message SET type=10000,content='你撤回了一条消息' WHERE msgId=10")
            db.execSQL("INSERT INTO message VALUES(124,1,0,124000,'someone-else','private')")
            val store = WechatHistoryStore("message", 0) { sql, args -> db.rawQuery(sql, args) }
            var request = LocalHistoryRequest("request", conversationId = "friend'quoted")
            val records = mutableListOf<com.jev.relationship.ipc.LocalChatRecord>()
            do {
                val page = store.read(request, "friend'quoted")
                records += page.records
                request = request.copy(afterTime = page.afterTime, afterId = page.afterId, upperId = page.upperId)
            } while (!page.complete)
            assertEquals(122, records.size)
            assertEquals(1L, records.first().id)
            assertEquals(123L, records.last().id)
            assertFalse(records.any { it.id == 10L || it.id == 124L })
            assertTrue(records.first().isOutgoing)
        }
    }
}
