package com.jev.relationship.core.model

data class DetailedAnalysis(
    val summary: String = "",
    val intention: String = "",
    val evidence: List<String> = emptyList(),
    val action: String = "",
    val reply: String = "",
    val contextual: Boolean = false,
    val failureReason: String? = null,
)
