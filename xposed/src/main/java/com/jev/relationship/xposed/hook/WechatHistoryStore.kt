package com.jev.relationship.xposed.hook

import android.database.Cursor
import com.jev.relationship.ipc.ChatTextPolicy
import com.jev.relationship.ipc.LocalChatRecord
import com.jev.relationship.ipc.LocalHistoryPage
import com.jev.relationship.ipc.LocalHistoryRequest

/** Read-only keyset pagination; no time window or recent-message limit. */
class WechatHistoryStore(
    private val table: String,
    private val deletedBefore: Long,
    private val query: (String, Array<String>) -> Cursor,
) {
    init { require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) }

    fun read(request: LocalHistoryRequest, talker: String): LocalHistoryPage {
        val upper = if (request.upperId > 0) request.upperId else {
            query("SELECT MAX(msgId) FROM $table WHERE talker=? AND type=1 AND createTime>?", arrayOf(talker, deletedBefore.toString())).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        }
        var afterTime = request.afterTime
        var afterId = request.afterId
        val records = mutableListOf<LocalChatRecord>()
        var chars = 0
        var consumed = 0
        var exhausted = true
        query(
            "SELECT msgId,content,createTime,isSend FROM $table WHERE talker=? AND type=1 AND createTime>? AND msgId<=? AND (createTime>? OR (createTime=? AND msgId>?)) ORDER BY createTime ASC,msgId ASC LIMIT ${LocalHistoryPage.PAGE_SIZE}",
            arrayOf(talker, deletedBefore.toString(), upper.toString(), request.afterTime.toString(), request.afterTime.toString(), request.afterId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.getString(1).orEmpty()
                check(text.length <= LocalHistoryPage.MAX_RECORD_TEXT) { "单条本地消息过长，未截断分析" }
                if (chars >= LocalHistoryPage.PAGE_TEXT_BUDGET) { exhausted = false; break }
                consumed++
                afterId = cursor.getLong(0)
                afterTime = cursor.getLong(2)
                if (ChatTextPolicy.isDialogue(text)) {
                    records += LocalChatRecord(afterId, text.trim(), afterTime, cursor.getInt(3) != 0)
                    chars += text.length
                }
            }
        }
        return LocalHistoryPage(request.requestId, talker, records, afterTime, afterId, upper, exhausted && consumed < LocalHistoryPage.PAGE_SIZE)
    }
}
