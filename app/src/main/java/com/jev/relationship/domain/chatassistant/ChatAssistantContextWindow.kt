package com.jev.relationship.domain.chatassistant

import com.jev.relationship.ipc.LocalChatRecord
import java.util.Locale

/** Local selection only: never calls a model or changes stored history. */
internal object ChatAssistantContextWindow {
    private const val RECENT_CHARS = 8_000
    private const val RELATED_CHARS = 2_000
    private const val TURN_CHARS = 4_000
    private const val OMITTED = "…[部分内容已省略]…"

    fun records(sorted: List<LocalChatRecord>, question: String?): List<LocalChatRecord> {
        val selected = sortedMapOf<Int, LocalChatRecord>()
        var remaining = RECENT_CHARS
        for (index in sorted.indices.reversed().take(60)) {
            if (remaining <= 80) break
            val record = sorted[index]
            val text = shorten(record.text, remaining - 40)
            selected[index] = record.copy(text = text)
            remaining -= text.length + 40 // Includes speaker, date, and newline overhead.
        }
        val recentStart = selected.firstKeyOrNull() ?: return emptyList()
        val terms = searchTerms(question.orEmpty())
        if (terms.isEmpty()) return selected.values.toList()
        val matches = (0 until recentStart).mapNotNull { index ->
            val text = sorted[index].text.lowercase(Locale.ROOT)
            val score = terms.count { text.contains(it) }
            if (score == 0) null else index to score
        }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        remaining = RELATED_CHARS
        for ((index, _) in matches.take(6)) {
            // Keep adjacent replies where possible, rather than isolated quotations.
            for (nearby in listOf(index, index - 1, index + 1)) {
                if (nearby !in sorted.indices || nearby in selected || remaining <= 80) continue
                val record = sorted[nearby]
                val text = shorten(record.text, remaining - 40)
                selected[nearby] = record.copy(text = text)
                remaining -= text.length + 40
            }
        }
        return selected.values.toList()
    }

    fun turns(turns: List<ChatAssistantConversationTurn>): List<ChatAssistantConversationTurn> {
        var remaining = TURN_CHARS
        val selected = mutableListOf<ChatAssistantConversationTurn>()
        for (turn in turns.takeLast(8).asReversed()) {
            if (remaining <= 80) break
            val content = shorten(turn.content, remaining - 20)
            selected += turn.copy(content = content)
            remaining -= content.length + 20
        }
        return selected.asReversed()
    }

    private fun searchTerms(question: String): Set<String> {
        val words = Regex("[a-z0-9_]{3,}|[\\p{IsHan}]{2,}").findAll(question.lowercase(Locale.ROOT))
        return words.flatMap { match ->
            val word = match.value
            if (word.first() in 'a'..'z' || word.first().isDigit()) sequenceOf(word)
            else word.windowed(2).asSequence()
        }.distinct().take(64).toSet()
    }

    private fun shorten(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val half = (limit - OMITTED.length) / 2
        return text.take(half) + OMITTED + text.takeLast(half)
    }

    private fun <V> java.util.SortedMap<Int, V>.firstKeyOrNull(): Int? = if (isEmpty()) null else firstKey()
}
