package com.jev.relationship.data.remote

import com.google.gson.JsonObject
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.AnalysisContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeSafeJevAnalyzerTest {
    @Test
    fun sendsTypedQuestionsAndComposesStructuredDecision() = runTest {
        val gateway = RecordingGateway()
        val analyzer = TypeSafeJevAnalyzer(gateway, TestSettingsRepository())

        val result = analyzer.analyze(
            AnalysisContext(
                conversation = Conversation("她：我最近有点焦虑", "contact-1"),
            ),
        )

        assertEquals("焦虑", result.emotion)
        assertEquals(listOf("需要安慰", "提出请求"), result.intents.map { it.name })
        assertEquals(0.72, result.intents.first().confidence, 0.0)
        assertEquals(0.28, result.intents.last().confidence, 0.0)
        assertEquals(8, result.riskLevel)
        assertTrue(result.suggestion.isNotBlank())
        assertTrue(gateway.questions.keys.containsAll(setOf("emotion", "intent", "risk", "concern", "literal_memory", "answer_now", "trust", "urgent", "resolved", "action", "need")))
        assertEquals("noul", gateway.questions.getValue("literal_memory").type)
        assertEquals("是否只是在确认你记不记得？", result.sections.first().title)
        assertEquals(0.07, result.sections.first().options.first().confidence, 0.0)
        assertEquals(0.93, result.sections.first().options.last().confidence, 0.00001)
        assertEquals("choice", gateway.questions.getValue("emotion").type)
        assertEquals("score", gateway.questions.getValue("risk").type)
        assertEquals("她：我最近有点焦虑", (gateway.state as JsonObject).get("conversation").asString)
    }

    private class RecordingGateway : TypeSafeJevGateway {
        lateinit var state: Any
        lateinit var questions: Map<String, TypeSafeQuestion>

        override suspend fun evaluate(
            settings: ProviderSettings,
            state: Any,
            questions: Map<String, TypeSafeQuestion>,
        ): TypeSafeJevResponse {
            this.state = state
            this.questions = questions
            return TypeSafeJevResponse(
                model = "jev-1.13.0",
                answers = mapOf(
                    "emotion" to TypeSafeJevAnswer(type = "choice", choice = "anxious", confidence = 0.95),
                    "intent" to TypeSafeJevAnswer(type = "choice", choice = "reassurance", confidence = 0.95,
                        probabilities = linkedMapOf("reassurance" to 0.72, "request" to 0.28)),
                    "risk" to TypeSafeJevAnswer(type = "score", score = 4.0),
                    "concern" to TypeSafeJevAnswer(type = "choice", choice = "memory"),
                    "literal_memory" to TypeSafeJevAnswer(type = "noul", noul = 0.07),
                ),
            )
        }
    }

    private class TestSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(
            ProviderSettings(
                baseUrl = "https://api.typesafe.ai/v1/",
                apiKey = "test-key",
                model = "jev-latest",
            ),
        )

        override val providerSettings: Flow<ProviderSettings> = state

        override suspend fun currentProviderSettings(): ProviderSettings = state.value

        override suspend fun saveProviderSettings(settings: ProviderSettings) {
            state.value = settings
        }
    }
}
