package com.jev.relationship.data.remote

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.AnalysisContext
import com.jev.relationship.domain.JevAnalyzer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedAnalysisEnricherTest {
    @Test
    fun reportsRateLimitAsFailureInsteadOfMissingContext() = runTest {
        val enricher = DetailedAnalysisEnricher(BaseAnalyzer(), TestSettingsRepository(ProviderSettings(
            replyBaseUrl = "https://model.example/v1/", replyApiKey = "key", replyModel = "test",
        )), complete = { _, _, _ -> throw retrofit2.HttpException(retrofit2.Response.error<String>(
            429, okhttp3.ResponseBody.create(null, "{}"))) })
        val result = enricher.analyze(Conversation("你好"))
        assertTrue(result.detailed.failureReason.orEmpty().contains("HTTP 429"))
        assertTrue(result.detailed.intention.contains("HTTP 429"))
        assertFalse(result.detailed.contextual)
    }

    @Test
    fun preservesAllSixtyLocalContextMessagesEvenWhenTheyExceedFourThousandCharacters() = runTest {
        val records = (1L..65L).map {
            com.jev.relationship.ipc.LocalChatRecord(it, "消息[$it]" + "具体上下文".repeat(20), it, false)
        }
        val conversation = com.jev.relationship.domain.source.LocalHistoryContext.forRecord(records, 64)
        var prompt = ""
        DetailedAnalysisEnricher(BaseAnalyzer(), TestSettingsRepository(ProviderSettings(
            replyBaseUrl = "https://model.example/v1/", replyApiKey = "key", replyModel = "test",
        )), complete = { _, _, user -> prompt = user; """{"intention":"回应当前消息"}""" })
            .analyze(conversation)
        assertTrue(prompt.contains("消息[6]"))
        assertTrue(prompt.contains("当前待分析消息：消息[65]"))
        assertFalse(prompt.contains("消息[5]"))
    }

    @Test
    fun resolvedAnalysisSkipsUnderstandingModel() = runTest {
        val base = object : JevAnalyzer {
            override suspend fun analyze(conversation: Conversation) = AnalysisResult("平静", emptyList(), 0, "停止", stopAfterAnalysis = true)
        }
        val result = DetailedAnalysisEnricher(base, TestSettingsRepository(ProviderSettings(
            replyBaseUrl = "https://model.example/v1/", replyApiKey = "key", replyModel = "test",
        )), complete = { _, _, _ -> error("No further model call after resolution") })
            .analyze(Conversation("这还差不多。"))
        assertTrue(result.stopAfterAnalysis)
        assertTrue(result.detailed.summary.isEmpty())
    }
    @Test
    fun usesUnderstandingModelOutputWhenReplyModelIsConfigured() = runTest {
        var prompt = ""
        val enricher = DetailedAnalysisEnricher(
            base = BaseAnalyzer(),
            settingsRepository = TestSettingsRepository(
                ProviderSettings(
                    apiKey = "jev-key",
                    replyBaseUrl = "https://model.example/v1/",
                    replyApiKey = "model-key",
                    replyModel = "understanding-model",
                ),
            ),
            complete = { _, _, userPrompt ->
                prompt = userPrompt
                """{"summary":"对方在认真追问","intention":"希望你明确回应","evidence":["使用了连续追问"],"action":"先正面回应","reply":"我看到了，给我一点时间说明。"}"""
            },
        )

        val result = enricher.analyze(AnalysisContext(Conversation("你到底怎么想？")))

        assertEquals("对方在认真追问", result.detailed.summary)
        assertTrue(result.detailed.contextual)
        assertTrue(prompt.contains("你到底怎么想？"))
    }

    @Test
    fun keepsTheTargetMessageWhenLongHistoryIsTrimmed() = runTest {
        var prompt = ""
        val oldOpening = "非常早的聊天开头标记"
        val target = "当前目标消息应该始终保留"
        val history = "$oldOpening\n" + (1..300).joinToString("\n") { "对方：旧上下文内容 $it" } +
            "\n\n当前待分析消息：$target"
        val enricher = DetailedAnalysisEnricher(
            base = BaseAnalyzer(),
            settingsRepository = TestSettingsRepository(
                ProviderSettings(
                    replyBaseUrl = "https://model.example/v1/",
                    replyApiKey = "model-key",
                    replyModel = "understanding-model",
                ),
            ),
            complete = { _, _, userPrompt ->
                prompt = userPrompt
                """{"intention":"对方想让你回应当前这件事。"}"""
            },
        )

        enricher.analyze(AnalysisContext(Conversation(history)))

        assertTrue(prompt.contains("当前待分析消息：$target"))
        assertTrue(prompt.contains("较早聊天已省略"))
        assertFalse(prompt.contains(oldOpening))
    }

    @Test
    fun fallsBackWithoutProbabilitiesWhenUnderstandingModelIsNotConfigured() = runTest {
        val result = DetailedAnalysisEnricher(
            base = BaseAnalyzer(),
            settingsRepository = TestSettingsRepository(ProviderSettings(apiKey = "jev-key")),
            complete = { _, _, _ -> error("should not call model") },
        ).analyze(AnalysisContext(Conversation("你好")))

        assertTrue(result.detailed.summary.isNotBlank())
        assertTrue(result.detailed.action.isNotBlank())
        assertTrue(result.detailed.evidence.isNotEmpty())
        assertTrue(result.detailed.reply.isNotBlank())
    }

    @Test
    fun doesNotReportUnconfiguredWhenConfiguredModelCallFails() = runTest {
        val result = DetailedAnalysisEnricher(
            base = BaseAnalyzer(),
            settingsRepository = TestSettingsRepository(
                ProviderSettings(
                    apiKey = "jev-key",
                    replyBaseUrl = "https://model.example/v1/",
                    replyApiKey = "model-key",
                    replyModel = "qwen3.8-flash",
                ),
            ),
            complete = { _, _, _ -> error("network failure") },
        ).analyze(AnalysisContext(Conversation("你好")))

        assertFalse(result.detailed.intention.contains("未配置"))
        assertTrue(result.detailed.intention.contains("已配置"))
    }

    private class BaseAnalyzer : JevAnalyzer {
        override suspend fun analyze(conversation: Conversation): AnalysisResult =
            AnalysisResult(
                emotion = "平静",
                intents = emptyList(),
                riskLevel = 0,
                suggestion = "保持自然回应。",
            )
    }

    private class TestSettingsRepository(settings: ProviderSettings) : SettingsRepository {
        private val state = MutableStateFlow(settings)
        override val providerSettings: Flow<ProviderSettings> = state
        override suspend fun currentProviderSettings(): ProviderSettings = state.value
        override suspend fun saveProviderSettings(settings: ProviderSettings) {
            state.value = settings
        }
    }
}
