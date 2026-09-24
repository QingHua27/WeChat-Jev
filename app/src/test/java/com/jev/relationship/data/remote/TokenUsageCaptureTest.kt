package com.jev.relationship.data.remote

import com.jev.relationship.data.usage.TokenUsageEvent
import com.jev.relationship.data.settings.OpenAiProviderSettings
import com.jev.relationship.data.settings.ProviderSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TokenUsageCaptureTest {
    @Test fun `stream cumulative usage is saved once and final usage wins`() = runBlocking {
        val server = MockWebServer()
        val events = mutableListOf<TokenUsageEvent>()
        server.enqueue(MockResponse().setBody("""
            data: {"usage":{"prompt_tokens":10,"completion_tokens":1},"choices":[{"delta":{"content":"OK"}}]}

            data: {"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15},"choices":[]}

            data: [DONE]

        """.trimIndent()))
        server.start()
        try {
            OpenAiCompatibleClient { events.add(it) }.complete(
                OpenAiProviderSettings(server.url("/").toString(), "test", "qwen-test"),
                listOf(ChatMessage("system", "a"), ChatMessage("user", "b")))
            assertEquals(1, events.size)
            assertEquals(15L, events.single().totalTokens)
            assertEquals(10L, events.single().inputTokens)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"include_usage\":true"))
        } finally { server.shutdown() }
    }

    @Test fun `Jev captures provider usage separately`() = runBlocking {
        val server = MockWebServer()
        val events = mutableListOf<TokenUsageEvent>()
        server.enqueue(MockResponse().setBody("""{"answers":{},"usage":{"input_tokens":30,"output_tokens":4}}"""))
        server.start()
        try {
            TypeSafeJevClient { events.add(it) }.evaluate(
                ProviderSettings(baseUrl = server.url("/").toString(), apiKey = "test"), "x", emptyMap())
            assertEquals("JEV", events.single().source)
            assertEquals(34L, events.single().totalTokens)
        } finally { server.shutdown() }
    }

    @Test fun `missing usage is unknown not zero and failed requests are recorded`() = runBlocking {
        val server = MockWebServer()
        val events = mutableListOf<TokenUsageEvent>()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        try {
            runCatching { OpenAiCompatibleClient { events.add(it) }.complete(
                OpenAiProviderSettings(server.url("/").toString(), "test", "test"),
                listOf(ChatMessage("system", "a"), ChatMessage("user", "b"))) }
            assertEquals(1, events.size)
            assertNull(events.single().totalTokens)
            assertFalse(events.single().succeeded)
        } finally { server.shutdown() }
    }
}
