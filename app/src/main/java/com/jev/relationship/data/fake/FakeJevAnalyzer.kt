package com.jev.relationship.data.fake

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.domain.JevAnalyzer
import com.jev.relationship.domain.normalizeConfidence
import com.jev.relationship.domain.normalizeRisk

class FakeJevAnalyzer : JevAnalyzer {
    override suspend fun analyze(conversation: Conversation): AnalysisResult {
        val text = conversation.text
        val isTense = text.contains("忘") || text.contains("怎么") || text.contains("是不是")
        return AnalysisResult(
            emotion = if (isTense) "不满" else "平静",
            intents = listOf(
                IntentProbability("希望被重视", normalizeConfidence(if (isTense) 0.72 else 0.35)),
                IntentProbability("确认事实", normalizeConfidence(if (isTense) 0.16 else 0.45)),
                IntentProbability("单纯分享", normalizeConfidence(if (isTense) 0.12 else 0.20)),
            ),
            riskLevel = normalizeRisk(if (isTense) 8 else 3),
            sections = listOf(com.jev.relationship.core.model.AnalysisSection(
                "本地演示", text = "尚未配置 Jev，当前不是模型分析结果。请在 Jev 设置中配置分析服务。",
            )),
            suggestion = if (isTense) "先回应情绪，再讨论具体事实。" else "保持自然回应，并确认对方真正想表达的重点。",
        )
    }
}
