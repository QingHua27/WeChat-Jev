package com.jev.relationship.data.remote

import com.google.gson.JsonParser
import com.jev.relationship.core.model.DetailedAnalysis

object DetailedAnalysisPayloadParser {
    fun parse(content: String): DetailedAnalysis {
        val normalizedContent = content.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        require(normalizedContent.isNotEmpty()) { "理解模型返回了空意图" }
        val objectStart = normalizedContent.indexOf('{')
        val objectEnd = normalizedContent.lastIndexOf('}')
        if (objectStart < 0 || objectEnd <= objectStart) {
            return DetailedAnalysis(summary = normalizedContent, intention = normalizedContent, contextual = true)
        }
        val root = runCatching {
            JsonParser.parseString(normalizedContent.substring(objectStart, objectEnd + 1)).asJsonObject
        }.getOrElse { throw IllegalArgumentException("理解模型返回的 JSON 无效", it) }
        fun optional(name: String): String = root.get(name)?.takeIf { it.isJsonPrimitive }
            ?.asString?.trim()?.takeIf { it.isNotEmpty() }
            .orEmpty()
        val intention = optional("intention")
        require(intention.isNotEmpty()) { "理解模型缺少字段: intention" }
        val evidence = root.getAsJsonArray("evidence")?.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty().take(3)
        return DetailedAnalysis(
            summary = optional("summary").ifBlank { intention },
            intention = intention,
            evidence = evidence,
            action = optional("action"),
            reply = optional("reply"),
            contextual = true,
        )
    }
}
