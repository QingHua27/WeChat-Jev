package com.jev.relationship.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object JevDatabaseMigrations {
    val VERSION_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE message_analysis_cache ADD COLUMN conversationHash TEXT NOT NULL DEFAULT ''")
            var before = Long.MAX_VALUE
            while (true) {
                val page = mutableListOf<Pair<Long, String>>()
                db.query("SELECT localMessageId, resultJson FROM message_analysis_cache WHERE localMessageId < ? ORDER BY localMessageId DESC LIMIT 128", arrayOf<Any>(before)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val hash = runCatching {
                            com.google.gson.JsonParser.parseString(cursor.getString(1)).asJsonObject
                                .get("conversationHash")?.asString.orEmpty()
                        }.getOrDefault("")
                        page += cursor.getLong(0) to hash
                    }
                }
                if (page.isEmpty()) break
                page.forEach { (id, hash) ->
                    db.execSQL("UPDATE message_analysis_cache SET conversationHash = ? WHERE localMessageId = ?", arrayOf(hash, id))
                }
                before = page.last().first
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_analysis_cache_conversationHash_localMessageId ON message_analysis_cache(conversationHash, localMessageId)")
        }
    }
    val VERSION_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_assistant_sessions (conversationId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_assistant_turns (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAtMs INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES chat_assistant_sessions(conversationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_assistant_turns_conversationId_id ON chat_assistant_turns(conversationId, id)")
        }
    }
}
