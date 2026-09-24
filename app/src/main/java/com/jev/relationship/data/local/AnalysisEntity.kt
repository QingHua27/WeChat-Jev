package com.jev.relationship.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.ReplyTone
import com.jev.relationship.core.model.SavedAnalysis
import com.jev.relationship.domain.AnalysisOutput

@Entity(tableName = "analysis_history")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationText: String,
    val emotion: String,
    val intentsJson: String,
    val riskLevel: Int,
    val suggestion: String,
    val repliesJson: String,
    val createdAt: Long,
) {
    fun toDomain(gson: Gson = Gson()): SavedAnalysis {
        val intentType = object : TypeToken<List<IntentProbability>>() {}.type
        val replyType = object : TypeToken<List<ReplySuggestion>>() {}.type
        return SavedAnalysis(
            id = id,
            conversation = Conversation(conversationText),
            output = AnalysisOutput(
                analysis = AnalysisResult(
                    emotion = emotion,
                    intents = gson.fromJson(intentsJson, intentType),
                    riskLevel = riskLevel,
                    suggestion = suggestion,
                ),
                replies = gson.fromJson(repliesJson, replyType),
            ),
            createdAt = createdAt,
        )
    }

    companion object {
        fun from(
            conversationText: String,
            output: AnalysisOutput,
            createdAt: Long,
            gson: Gson = Gson(),
        ): AnalysisEntity = AnalysisEntity(
            conversationText = conversationText,
            emotion = output.analysis.emotion,
            intentsJson = gson.toJson(output.analysis.intents),
            riskLevel = output.analysis.riskLevel,
            suggestion = output.analysis.suggestion,
            repliesJson = gson.toJson(output.replies),
            createdAt = createdAt,
        )
    }
}

