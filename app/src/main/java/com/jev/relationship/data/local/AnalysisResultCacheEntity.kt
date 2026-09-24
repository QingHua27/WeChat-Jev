package com.jev.relationship.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jev.relationship.ipc.IpcAnalysisResult

@Entity(tableName = "message_analysis_cache", indices = [androidx.room.Index(value = ["conversationHash", "localMessageId"])])
data class AnalysisResultCacheEntity(
    @PrimaryKey val localMessageId: Long,
    val resultJson: String,
    val cachedAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "''") val conversationHash: String = "",
) {
    fun toResult(gson: Gson = Gson()): IpcAnalysisResult =
        gson.fromJson(resultJson, IpcAnalysisResult::class.java)

    fun hasTargetPreservingContext(): Boolean = runCatching {
        JsonParser.parseString(resultJson).asJsonObject.get(CONTEXT_VERSION)?.asInt == 1
    }.getOrDefault(false)

    companion object {
        private const val CONTEXT_VERSION = "targetContextVersion"

        fun from(messageId: Long, result: IpcAnalysisResult, gson: Gson = Gson()) =
            AnalysisResultCacheEntity(messageId, gson.toJson(gson.toJsonTree(result).asJsonObject.apply {
                addProperty(CONTEXT_VERSION, 1)
            }), System.currentTimeMillis(), result.conversationHash)
    }
}
