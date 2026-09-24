package com.jev.relationship.data.local

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.ReplyTone
import com.jev.relationship.domain.AnalysisOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisEntityMapperTest {
    @Test
    fun entityRoundTripPreservesStructuredAnalysis() {
        val output = AnalysisOutput(
            analysis = AnalysisResult(
                emotion = "不满",
                intents = listOf(IntentProbability("希望被重视", 0.72)),
                riskLevel = 8,
                suggestion = "先回应情绪",
            ),
            replies = listOf(ReplySuggestion(ReplyTone.Gentle, "我在听")),
        )
        val entity = AnalysisEntity.from(
            conversationText = "你：怎么了？",
            output = output,
            createdAt = 123L,
        )

        assertEquals("你：怎么了？", entity.conversationText)
        assertEquals(output, entity.toDomain().output)
        assertEquals(123L, entity.createdAt)
    }
}

