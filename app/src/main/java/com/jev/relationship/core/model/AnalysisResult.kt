package com.jev.relationship.core.model

data class IntentProbability(
    val name: String,
    val confidence: Double,
)

data class AnalysisSection(
    val title: String,
    val options: List<IntentProbability> = emptyList(),
    val text: String = "",
)

data class AnalysisResult(
    val emotion: String,
    val intents: List<IntentProbability>,
    val riskLevel: Int,
    val suggestion: String,
    val detailed: DetailedAnalysis = DetailedAnalysis(),
    val sections: List<AnalysisSection> = emptyList(),
    val stopAfterAnalysis: Boolean = false,
)
