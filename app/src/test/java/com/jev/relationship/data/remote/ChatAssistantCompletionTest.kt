package com.jev.relationship.data.remote

import com.google.gson.JsonParser
import com.jev.relationship.data.settings.OpenAiProviderSettings
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAssistantCompletionTest {
    @Test
    fun `explicit finish reason also completes providers without a done sentinel`() = kotlinx.coroutines.runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n"))
        server.start()
        try {
            assertEquals("answer", OpenAiCompatibleClient().complete(
                OpenAiProviderSettings(server.url("/").toString(), "key", "model"),
                listOf(ChatMessage("system", "rules"), ChatMessage("user", "test"))))
        } finally { server.shutdown() }
    }

    @Test
    fun `stream ending without a completion marker does not return an unfinished answer`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"unfinished\"}}]}\n\n"))
        server.start()
        val partials = mutableListOf<String>()
        try {
            val failure = runCatching { kotlinx.coroutines.runBlocking {
                OpenAiCompatibleClient().stream(
                    OpenAiProviderSettings(server.url("/").toString(), "key", "model"),
                    listOf(ChatMessage("system", "rules"), ChatMessage("user", "test")),
                    partials::add,
                )
            } }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(listOf("unfinished"), partials)
        } finally { server.shutdown() }
    }

    @Test
    fun `stream exposes first answer before the server finishes and never exposes reasoning`() {
        val first = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(first + ":".repeat(1024 - first.toByteArray().size) + "\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\ndata: [DONE]\n\n")
            .throttleBody(1024, 4, java.util.concurrent.TimeUnit.SECONDS))
        server.start()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val firstChunk = java.util.concurrent.CountDownLatch(1)
        val partials = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            val answer = executor.submit<String> {
                kotlinx.coroutines.runBlocking {
                    OpenAiCompatibleClient().stream(
                        OpenAiProviderSettings(server.url("/").toString(), "test-key", "model"),
                        listOf(ChatMessage("system", "rules"), ChatMessage("user", "test")),
                    ) { partials += it; firstChunk.countDown() }
                }
            }
            assertTrue("First text must arrive before completion", firstChunk.await(2, java.util.concurrent.TimeUnit.SECONDS))
            org.junit.Assert.assertFalse(answer.isDone)
            assertEquals("hello", partials.first())
            assertEquals("hello world", answer.get(6, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("hello world", partials.last())
            org.junit.Assert.assertFalse(partials.any { it.contains("private") })
        } finally {
            server.shutdown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `http failure retains status for the assistant error message`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.start()
        try {
            val failure = runCatching {
                kotlinx.coroutines.runBlocking {
                    OpenAiCompatibleClient().complete(
                        OpenAiProviderSettings(server.url("/").toString(), "test-key", "model"),
                        listOf(ChatMessage("system", "rules"), ChatMessage("user", "test")),
                    )
                }
            }.exceptionOrNull()
            assertEquals(401, (failure as retrofit2.HttpException).code())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `done marker returns answer without waiting for response body to close`() {
        val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"}}]}\n\ndata: [DONE]\n\n"
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(stream + ":".repeat(1024 - stream.toByteArray().size) + " delayed keepalive\n\n")
            .throttleBody(1024, 3, java.util.concurrent.TimeUnit.SECONDS))
        server.start()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val answer = executor.submit<String> {
                kotlinx.coroutines.runBlocking {
                    OpenAiCompatibleClient().complete(
                        OpenAiProviderSettings(server.url("/").toString(), "test-key", "model"),
                        listOf(ChatMessage("system", "rules"), ChatMessage("user", "test")),
                    )
                }
            }
            assertEquals("ready", answer.get(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            server.shutdown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling a streamed request interrupts the response body read`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(":".repeat(1024) + " delayed content\n\n")
            .throttleBody(1024, 3, java.util.concurrent.TimeUnit.SECONDS))
        server.start()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val cancelled = executor.submit<Boolean> {
                kotlinx.coroutines.runBlocking {
                    val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                        OpenAiCompatibleClient().complete(
                            OpenAiProviderSettings(server.url("/").toString(), "test-key", "model"),
                            listOf(ChatMessage("system", "rules"), ChatMessage("user", "test")),
                        )
                    }
                    checkNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
                    kotlinx.coroutines.delay(150)
                    job.cancel()
                    job.join()
                    job.isCancelled
                }
            }
            assertTrue(cancelled.get(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            server.shutdown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `completion sends all ordered turns to configured model`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """data: {"choices":[{"delta":{"content":"整体分析"}}]}""" + "\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()
        try {
            val answer = kotlinx.coroutines.runBlocking {
                OpenAiCompatibleClient().complete(
                    settings = OpenAiProviderSettings(server.url("v1/").toString(), "secret", "understanding-model"),
                    messages = listOf(
                        ChatMessage("system", "rules"),
                        ChatMessage("system", "latest transcript"),
                        ChatMessage("assistant", "prior report"),
                        ChatMessage("user", "follow-up"),
                    ),
                )
            }

            val request = server.takeRequest()
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            val messages = json.getAsJsonArray("messages").map { it.asJsonObject }
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("understanding-model", json.get("model").asString)
            assertEquals(listOf("system", "system", "assistant", "user"), messages.map { it.get("role").asString })
            assertEquals(listOf("rules", "latest transcript", "prior report", "follow-up"), messages.map { it.get("content").asString })
            assertEquals("整体分析", answer)
            assertTrue(server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) == null)
        } finally {
            server.shutdown()
        }
    }
}
