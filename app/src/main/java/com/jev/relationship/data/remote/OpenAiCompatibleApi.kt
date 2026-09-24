package com.jev.relationship.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.SkipCallbackExecutor
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val stream: Boolean = true,
    @SerializedName("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
    @SerializedName("enable_thinking") val enableThinking: Boolean? = null,
    val thinking: ThinkingMode? = null,
)

data class ThinkingMode(val type: String = "disabled")
data class StreamOptions(@SerializedName("include_usage") val includeUsage: Boolean = true)

fun interface ChatAssistantCompletion {
    suspend fun complete(settings: com.jev.relationship.data.settings.OpenAiProviderSettings, messages: List<ChatMessage>): String

    suspend fun stream(
        settings: com.jev.relationship.data.settings.OpenAiProviderSettings,
        messages: List<ChatMessage>,
        onPartial: (String) -> Unit,
    ): String = complete(settings, messages).also(onPartial)
}

interface OpenAiCompatibleApi {
    @SkipCallbackExecutor
    @Streaming
    @POST("chat/completions")
    fun complete(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest,
    ): Call<ResponseBody>
}
