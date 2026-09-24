package com.jev.relationship.ipc

import android.os.Bundle

data class LocalChatRecord(val id: Long, val text: String, val timestampMs: Long, val isOutgoing: Boolean)

data class LocalHistoryRequest(
    val requestId: String,
    val conversationId: String = "",
    val title: String = "",
    val afterTime: Long = 0,
    val afterId: Long = 0,
    val upperId: Long = 0,
) {
    fun toBundle() = Bundle().apply {
        putString("request", requestId); putString("talker", conversationId); putString("title", title)
        putLong("time", afterTime); putLong("id", afterId); putLong("upper", upperId)
    }
    companion object {
        fun fromBundle(b: Bundle): LocalHistoryRequest {
            val request = LocalHistoryRequest(b.getString("request").orEmpty(), b.getString("talker").orEmpty(), b.getString("title").orEmpty(), b.getLong("time"), b.getLong("id"), b.getLong("upper"))
            require(request.requestId.length in 1..80 && request.conversationId.length <= 256 && request.title.length <= 256)
            require(request.afterTime >= 0 && request.afterId >= 0 && request.upperId >= 0)
            return request
        }
    }
}

data class LocalHistoryPage(
    val requestId: String,
    val conversationId: String,
    val records: List<LocalChatRecord> = emptyList(),
    val afterTime: Long = 0,
    val afterId: Long = 0,
    val upperId: Long = 0,
    val complete: Boolean = false,
    val error: String? = null,
) {
    fun toBundle() = Bundle().apply {
        putString("request", requestId); putString("talker", conversationId)
        putLong("time", afterTime); putLong("id", afterId); putLong("upper", upperId)
        putBoolean("complete", complete); putString("error", error)
        putParcelableArrayList("records", ArrayList(records.map { record -> Bundle().apply {
            putLong("id", record.id); putString("text", record.text)
            putLong("time", record.timestampMs); putBoolean("outgoing", record.isOutgoing)
        } }))
    }
    companion object {
        const val PAGE_SIZE = 50
        const val PAGE_TEXT_BUDGET = 24_000
        const val MAX_RECORD_TEXT = 64_000
        @Suppress("DEPRECATION")
        fun fromBundle(b: Bundle): LocalHistoryPage {
            val raw = b.getParcelableArrayList<Bundle>("records").orEmpty()
            require(raw.size <= PAGE_SIZE)
            val records = raw.map {
                LocalChatRecord(it.getLong("id"), it.getString("text").orEmpty(), it.getLong("time"), it.getBoolean("outgoing"))
            }
            require(records.all { it.id > 0 && it.timestampMs >= 0 && it.text.length <= MAX_RECORD_TEXT })
            require(records.sumOf { it.text.length } <= PAGE_TEXT_BUDGET + MAX_RECORD_TEXT)
            val result = LocalHistoryPage(b.getString("request").orEmpty(), b.getString("talker").orEmpty(), records, b.getLong("time"), b.getLong("id"), b.getLong("upper"), b.getBoolean("complete"), b.getString("error"))
            require(result.requestId.length in 1..80 && result.conversationId.length <= 256 && (result.error?.length ?: 0) <= 256)
            return result
        }
    }
}
