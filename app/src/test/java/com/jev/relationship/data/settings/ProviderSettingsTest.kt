package com.jev.relationship.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingsTest {
    @Test
    fun JevDefaultsUseTheOfficialEndpointAndModel() {
        val settings = ProviderSettings()

        assertEquals(JevProviderDefaults.BASE_URL, settings.baseUrl)
        assertEquals(JevProviderDefaults.MODEL, settings.model)
    }

    @Test
    fun JevOnlyConfigurationDoesNotPretendReplyGenerationIsConfigured() {
        val settings = ProviderSettings(
            baseUrl = "https://api.typesafe.ai/v1",
            apiKey = "jev-key",
            model = "jev-latest",
        )

        assertTrue(settings.isConfigured)
        assertFalse(settings.replySettings().isConfigured)
    }

    @Test
    fun replySettingsAreIndependentFromJevSettings() {
        val settings = ProviderSettings(
            baseUrl = "https://api.typesafe.ai/v1",
            apiKey = "jev-key",
            model = "jev-latest",
            replyBaseUrl = "https://api.openai.com/v1",
            replyApiKey = "reply-key",
            replyModel = "gpt-4.1-mini",
        )

        val reply = settings.replySettings()

        assertTrue(reply.isConfigured)
        assertEquals("https://api.openai.com/v1", reply.baseUrl)
        assertEquals("reply-key", reply.apiKey)
        assertEquals("gpt-4.1-mini", reply.model)
    }

    @Test
    fun bailianWorkspacePlaceholderIsRejectedWithAUsableEndpointHint() {
        val failure = runCatching {
            OpenAiProviderSettings(
                baseUrl = "https://[workspace-id].cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                apiKey = "key",
                model = "qwen3.8-flash",
            ).normalizedBaseUrl()
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("dashscope.aliyuncs.com/compatible-mode/v1"))
    }
}
