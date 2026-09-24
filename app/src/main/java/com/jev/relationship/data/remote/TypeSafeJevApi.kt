package com.jev.relationship.data.remote

import com.jev.relationship.data.settings.ProviderSettings
import com.google.gson.JsonObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import com.jev.relationship.data.usage.*

data class TypeSafeQuestion(
    val type: String,
    val instructions: String,
    val criteria: Any? = null,
)

data class TypeSafeJevRequest(
    val state: Any,
    val model: String,
    val questions: Map<String, TypeSafeQuestion>,
)

data class TypeSafeJevAnswer(
    val type: String? = null,
    val choice: String? = null,
    val probabilities: Map<String, Double>? = null,
    val confidence: Double? = null,
    val score: Double? = null,
    val noul: Double? = null,
)

data class TypeSafeJevResponse(
    val model: String? = null,
    val answers: Map<String, TypeSafeJevAnswer> = emptyMap(),
    val usage: JsonObject? = null,
)

interface TypeSafeJevApi {
    @POST("systemone")
    suspend fun evaluate(
        @Header("Authorization") authorization: String,
        @Body request: TypeSafeJevRequest,
    ): TypeSafeJevResponse
}

interface TypeSafeJevGateway {
    suspend fun evaluate(
        settings: ProviderSettings,
        state: Any,
        questions: Map<String, TypeSafeQuestion>,
    ): TypeSafeJevResponse
}

class TypeSafeJevClient(private val recordUsage: suspend (TokenUsageEvent) -> Unit = {}) : TypeSafeJevGateway {
    override suspend fun evaluate(
        settings: ProviderSettings,
        state: Any,
        questions: Map<String, TypeSafeQuestion>,
    ): TypeSafeJevResponse {
        require(settings.isConfigured) { "Jev provider is not configured" }
        val api = Retrofit.Builder()
            .baseUrl(settings.normalizedBaseUrl())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TypeSafeJevApi::class.java)
        var reported: ReportedTokens? = null
        var succeeded = false
        try {
            return api.evaluate(
                authorization = "Bearer ${settings.apiKey}",
                request = TypeSafeJevRequest(
                    state = state,
                    model = settings.model,
                    questions = questions,
                ),
            ).also {
                reported = ReportedTokens.parse(it.usage)
                succeeded = true
            }
        } finally {
            recordUsageSafely(recordUsage, TokenUsageEvent(source = UsageSource.JEV.name,
                model = settings.model, inputTokens = reported?.input, outputTokens = reported?.output,
                totalTokens = reported?.total, succeeded = succeeded))
        }
    }
}

object TypeSafeJevQuestionFactory {
    fun choiceCriteria(values: Map<String, String>): JsonObject = JsonObject().apply {
        values.forEach { (key, description) -> addProperty(key, description) }
    }
}
