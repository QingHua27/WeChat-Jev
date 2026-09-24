package com.jev.relationship.data.remote

import com.google.gson.JsonParser
import com.jev.relationship.data.settings.OpenAiProviderSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClientTest {
    @Test
    fun `GLM uses its own thinking switch instead of the Qwen parameter`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"))
        server.start()
        try {
            OpenAiCompatibleClient().complete(OpenAiProviderSettings(server.url("/").toString(), "test-key", "GLM-4.7-Flash"),
                listOf(ChatMessage("system", "system"), ChatMessage("user", "hello")))
            val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("disabled", body.getAsJsonObject("thinking")?.get("type")?.asString)
            assertFalse(body.has("enable_thinking"))
        } finally { server.shutdown() }
    }

    @Test
    fun `requests fast answers without deep thinking and filters reasoning from streamed output`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"报告\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()

        try {
            val response = OpenAiCompatibleClient().complete(
                settings = OpenAiProviderSettings(
                    baseUrl = server.url("compatible-mode/v1").toString(),
                    apiKey = "test-key",
                    model = "qwen3.8-flash",
                ),
                messages = listOf(
                    ChatMessage("system", "system"),
                    ChatMessage("user", "conversation"),
                ),
            )

            assertEquals("报告完成", response)
            val request = server.takeRequest()
            assertEquals("/compatible-mode/v1/chat/completions", request.path)
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(true, body.get("stream").asBoolean)
            assertEquals(false, body.get("enable_thinking").asBoolean)
            assertEquals("qwen3.8-flash", body.get("model").asString)
            assertFalse(response.contains("private reasoning"))
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 30_000)
    fun `model response arriving after default OkHttp timeout is accepted`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"report ready\"}}]}\n\ndata: [DONE]\n\n")
                .setBodyDelay(12, TimeUnit.SECONDS),
        )
        server.start()

        try {
            val response = OpenAiCompatibleClient().complete(
                settings = OpenAiProviderSettings(
                    baseUrl = server.url("/").toString(),
                    apiKey = "test-key",
                    model = "test-model",
                ),
                messages = listOf(
                    ChatMessage("system", "system"),
                    ChatMessage("user", "conversation"),
                ),
            )

            assertEquals("report ready", response)
        } finally {
            server.shutdown()
        }
    }
}
