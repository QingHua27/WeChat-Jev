package com.jev.relationship.data.remote

import org.junit.Assert.*
import org.junit.Test

class ConversationJudgmentsTest {
    @Test fun usesDistinctQuestionsForEachConversationStage() {
        val binary = TypeSafeJevAnswer(noul = 0.18)
        fun sections(stage: String) = ConversationJudgments.sections(TypeSafeJevResponse(answers = mapOf(
            "concern" to TypeSafeJevAnswer(choice = stage),
            "answer_now" to binary, "trust" to binary, "urgent" to binary, "resolved" to binary,
            "need" to TypeSafeJevAnswer(probabilities = mapOf("action" to 0.74, "apology" to 0.21, "explanation" to 0.05)),
        )), emptyList(), 2, "先确认。")
        assertEquals("是否应该立刻回答具体内容？", sections("answer").first().title)
        assertEquals(listOf("这句话是否代表相信？", "是否进入紧急模式？"), sections("trust").map { it.title })
        assertEquals("行动", sections("need").single().options.first().name)
        assertEquals(0.74, sections("need").single().options.first().confidence, 0.0)
        assertEquals("危机是否解除？", sections("resolved").first().title)
    }

    @Test fun stopsOnlyWhenResolvedJudgmentIsStrongAndApplicable() {
        fun response(stage: String, probability: Double) = TypeSafeJevResponse(answers = mapOf(
            "concern" to TypeSafeJevAnswer(choice = stage), "resolved" to TypeSafeJevAnswer(noul = probability),
        ))
        assertTrue(ConversationJudgments.shouldStop(response("resolved", 0.94)))
        assertFalse(ConversationJudgments.shouldStop(response("resolved", 0.51)))
        assertFalse(ConversationJudgments.shouldStop(response("general", 0.99)))
        assertFalse(ConversationJudgments.shouldStop(response("resolved", Double.NaN)))
    }

    @Test fun neverInventsMissingOrInvalidBinaryProbabilities() {
        val response = TypeSafeJevResponse(answers = mapOf(
            "concern" to TypeSafeJevAnswer(choice = "trust"), "trust" to TypeSafeJevAnswer(noul = Double.NaN),
        ))
        assertTrue(ConversationJudgments.sections(response, emptyList(), 1, "建议").isEmpty())
    }
}
