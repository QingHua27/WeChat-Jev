package com.jev.relationship.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.jev.relationship.data.settings.OpenAiProviderSettings
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.jev.relationship.data.usage.*
import java.util.concurrent.atomic.AtomicReference

class OpenAiCompatibleClient(private val recordUsage: suspend (TokenUsageEvent) -> Unit = {}) : ChatAssistantCompletion {
    suspend fun complete(
        settings: OpenAiProviderSettings,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        return complete(
            settings = settings,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
        )
    }

    override suspend fun complete(
        settings: OpenAiProviderSettings,
        messages: List<ChatMessage>,
    ): String = stream(settings, messages) {}

    override suspend fun stream(
        settings: OpenAiProviderSettings,
        messages: List<ChatMessage>,
        onPartial: (String) -> Unit,
    ): String {
        require(settings.isConfigured) { "AI provider is not configured" }
        require(messages.size >= 2) { "AI conversation must include system context and a user message" }
        val api = Retrofit.Builder()
            .baseUrl(settings.normalizedBaseUrl())
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(150, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(160, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(OpenAiCompatibleApi::class.java)
        val call = api.complete(
            authorization = "Bearer ${settings.apiKey}",
            request = ChatCompletionRequest(
                model = settings.model,
                messages = messages,
                // Interactive chat favors a quick answer over a long reasoning phase.
                enableThinking = if (settings.model.startsWith("qwen", ignoreCase = true)) false else null,
                thinking = if (settings.model.startsWith("glm-4.", ignoreCase = true)) ThinkingMode() else null,
                // BigModel returns usage itself and does not document stream_options.
                streamOptions = if (java.net.URI(settings.normalizedBaseUrl()).host?.endsWith(".bigmodel.cn") == true) null else StreamOptions(),
            ),
        )
        // Keep cancellation attached to the HTTP call until the streamed body
        // finishes, not just until Retrofit receives the response headers.
        val startedAt = System.nanoTime()
        val traceId = Integer.toHexString(System.identityHashCode(call))
        fun trace(event: String, error: Throwable? = null) {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            // Never log credentials, prompts, responses, URLs or exception messages.
            runCatching {
                Log.i("JevModelRequest", "request=$traceId $event elapsedMs=$elapsedMs type=${error?.javaClass?.simpleName.orEmpty()} " +
                    "cause=${error?.cause?.javaClass?.simpleName.orEmpty()} cancelled=${call.isCanceled}")
            }
        }
        trace("start inputChars=${messages.sumOf { it.content.length.toLong() }}")
        val reportedUsage = AtomicReference<ReportedTokens?>(null)
        var succeeded = false
        try {
            return suspendCancellableCoroutine<String> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback<ResponseBody> {
                    override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                        trace("headers status=${response.code()}")
                        try {
                            if (!response.isSuccessful) {
                                response.errorBody()?.close()
                                throw HttpException(response)
                            }
                            val answer = response.body()?.use { body ->
                                readStreamedContent(body, { event -> trace(event) }, { reportedUsage.set(it) }) { text ->
                                    if (continuation.isActive) onPartial(text)
                                }
                            }
                                ?.takeIf { it.isNotEmpty() }
                                ?: throw IllegalArgumentException("AI provider returned an empty response")
                            trace("complete")
                            continuation.resume(answer)
                        } catch (error: Exception) {
                            trace("body_failure", error)
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onFailure(call: Call<ResponseBody>, error: Throwable) {
                        trace("connection_failure", error)
                        continuation.resumeWithException(error)
                    }
                })
            }.also { succeeded = true }
        } finally {
            val usage = reportedUsage.get()
            recordUsageSafely(recordUsage, TokenUsageEvent(source = UsageSource.UNDERSTANDING.name,
                model = settings.model, inputTokens = usage?.input, outputTokens = usage?.output,
                totalTokens = usage?.total, succeeded = succeeded))
        }
    }

    private fun readStreamedContent(response: ResponseBody, trace: (String) -> Unit,
        onUsage: (ReportedTokens) -> Unit, onPartial: (String) -> Unit): String {
        val answer = StringBuilder()
        val source = response.source()
        var sawReasoning = false
        var completed = false
        var lastEmittedAt = 0L
        var emittedLength = 0
        fun emit(force: Boolean = false) {
            val now = System.nanoTime()
            if (answer.length > emittedLength && (force || emittedLength == 0 || now - lastEmittedAt >= 80_000_000L)) {
                onPartial(answer.toString())
                emittedLength = answer.length
                lastEmittedAt = now
            }
        }
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substringAfter("data:").trim()
            if (data == "[DONE]") {
                completed = true
                break
            }
            if (data.isEmpty()) continue

            val event = JsonParser.parseString(data)
            if (!event.isJsonObject) continue
            val usage = event.asJsonObject.get("usage")
            ReportedTokens.parse(usage)?.let(onUsage)
            if (usage?.isJsonObject == true) {
                fun count(name: String): Long? = runCatching {
                    usage.asJsonObject.get(name)?.asLong?.takeIf { it >= 0 }
                }.getOrNull()
                val input = count("prompt_tokens")
                val output = count("completion_tokens")
                if (input != null && output != null) trace("usage inputTokens=$input outputTokens=$output")
            }
            val choices = event.asJsonObject.getAsJsonArray("choices") ?: continue
            val choice = choices.firstOrNull()?.asJsonObject ?: continue
            val finishReason = choice.get("finish_reason")
            if (finishReason?.isJsonPrimitive == true && finishReason.asString.isNotBlank()) completed = true
            val delta = choice.getAsJsonObject("delta") ?: continue
            val reasoning = delta.get("reasoning_content")
            if (!sawReasoning && reasoning?.isJsonPrimitive == true && reasoning.asString.isNotEmpty()) {
                sawReasoning = true
                trace("reasoning_started")
            }
            val content = delta.get("content")
            if (content?.isJsonPrimitive == true && content.asJsonPrimitive.isString) {
                if (answer.isEmpty() && content.asString.isNotEmpty()) trace("answer_started")
                answer.append(content.asString)
                emit()
            }
        }
        if (!completed) throw java.io.IOException("Model stream ended before completion")
        emit(force = true)
        return answer.toString().trim()
    }
}
