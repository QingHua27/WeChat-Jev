package com.jev.relationship.domain.source

data class AccessibilityTextNode(
    val packageName: String,
    val text: String,
    val isVisible: Boolean,
)

object AccessibilityMessageParser {
    fun parse(nodes: List<AccessibilityTextNode>, allowedPackages: Set<String>): String? {
        val messages = nodes.asSequence()
            .filter { it.isVisible && it.packageName in allowedPackages }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

