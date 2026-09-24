package com.jev.relationship.data.settings

object JevProviderDefaults {
    const val BASE_URL = "https://api.typesafe.ai/v1/"
    const val MODEL = "jev-latest"
}

data class ProviderSettings(
    val baseUrl: String = JevProviderDefaults.BASE_URL,
    val apiKey: String = "",
    val model: String = JevProviderDefaults.MODEL,
    val replyBaseUrl: String = "",
    val replyApiKey: String = "",
    val replyModel: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun replySettings(): OpenAiProviderSettings = OpenAiProviderSettings(
        baseUrl = replyBaseUrl,
        apiKey = replyApiKey,
        model = replyModel,
    )

    fun normalizedBaseUrl(): String {
        return normalizeOpenAiBaseUrl(baseUrl)
    }
}

data class OpenAiProviderSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun normalizedBaseUrl(): String {
        return normalizeOpenAiBaseUrl(baseUrl)
    }
}

data class ReplyModelPreset(
    val id: String,
    val name: String,
    val settings: OpenAiProviderSettings,
)

private fun normalizeOpenAiBaseUrl(baseUrl: String): String {
    val value = baseUrl.trim()
    require(value.startsWith("http://") || value.startsWith("https://")) {
        "Provider URL must start with http:// or https://"
    }
    val workspacePlaceholders = listOf(
        "[workspace-id]",
        "{workspace-id}",
        "{workspaceid}",
        "<workspace-id>",
        "<workspaceid>",
    )
    require(workspacePlaceholders.none { value.contains(it, ignoreCase = true) }) {
        "请将百炼 URL 中的 workspace-id 替换为实际业务空间 ID；北京地域也可使用 https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
    return if (value.endsWith('/')) value else "$value/"
}
