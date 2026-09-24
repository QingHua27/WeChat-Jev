package com.jev.relationship.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.ReplyTone

object RemotePayloadParser {
    fun parseReplies(rawJson: String): List<ReplySuggestion> {
        val root = parseObject(rawJson)
        return root.getAsJsonArray("replies")?.map { item ->
            val reply = item.asJsonObject
            ReplySuggestion(
                tone = when (reply.requiredString("tone").lowercase()) {
                    "gentle" -> ReplyTone.Gentle
                    "humorous" -> ReplyTone.Humorous
                    "serious" -> ReplyTone.Serious
                    else -> throw IllegalArgumentException("Unknown reply tone")
                },
                text = reply.requiredString("text"),
            )
        } ?: throw IllegalArgumentException("Missing replies")
    }

    private fun parseObject(rawJson: String): JsonObject = try {
        JsonParser.parseString(rawJson).asJsonObject
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Invalid provider JSON", error)
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing $key")

}
