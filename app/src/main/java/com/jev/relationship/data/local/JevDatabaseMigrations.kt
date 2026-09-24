package com.jev.relationship.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object JevDatabaseMigrations {
    val VERSION_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_assistant_sessions (conversationId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_assistant_turns (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAtMs INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES chat_assistant_sessions(conversationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_assistant_turns_conversationId_id ON chat_assistant_turns(conversationId, id)")
        }
    }
}
