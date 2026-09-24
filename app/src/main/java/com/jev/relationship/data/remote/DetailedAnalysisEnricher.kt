package com.jev.relationship.data.remote

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.DetailedAnalysis
import com.jev.relationship.data.settings.OpenAiProviderSettings
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.AnalysisContext
import com.jev.relationship.domain.JevAnalyzer
import android.util.Log
import retrofit2.HttpException
import kotlinx.coroutines.CancellationException
import com.jev.relationship.domain.chatassistant.ChatAssistantErrorMessage

class DetailedAnalysisEnricher(
    private val base: JevAnalyzer,
    private val settingsRepository: SettingsRepository,
    private val complete: suspend (OpenAiProviderSettings, String, String) -> String,
) : JevAnalyzer {
    override suspend fun analyze(conversation: com.jev.relationship.core.model.Conversation): AnalysisResult =
        analyze(AnalysisContext(conversation))

    override suspend fun analyze(context: AnalysisContext): AnalysisResult {
        val baseResult = base.analyze(context)
        if (baseResult.stopAfterAnalysis) return baseResult
        val settings = settingsRepository.currentProviderSettings().replySettings()
        if (!settings.isConfigured) return baseResult.withFallbackDetail(
            "未配置理解模型，暂时只能给出结构化判断，无法生成更细的上下文解释。",
        )
        return runCatching {
            val content = complete(settings, SYSTEM_PROMPT, userPrompt(context, baseResult))
            baseResult.copy(detailed = DetailedAnalysisPayloadParser.parse(content))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val cause = error.cause
            val status = (error as? HttpException)?.code()
            runCatching {
                Log.w(
                    LOG_TAG,
                    "understanding_failed type=${error.javaClass.simpleName} cause=${cause?.javaClass?.simpleName.orEmpty()} httpStatus=${status ?: "none"}",
                )
            }
            baseResult.withFallbackDetail(
                "理解模型已配置，但本次调用失败：${ChatAssistantErrorMessage.modelRequest(error)}",
            )
        }
    }

    private fun userPrompt(context: AnalysisContext, baseResult: AnalysisResult): String = buildString {
        appendLine("请只解释聊天末尾明确标记为“当前待分析消息”的这条消息，结合前文说明它的实际意思和诉求。")
        appendLine("聊天上下文：")
        appendLine(if (context.conversation.hasBoundedMessageContext) context.conversation.text
            else contextWindow(context.conversation.text))
        appendLine()
        appendLine("基础判断（仅供参考，不要照抄概率）：")
        appendLine("情绪：${baseResult.emotion}")
        appendLine("主要意图：${baseResult.intents.firstOrNull()?.name ?: "未识别"}")
        appendLine("风险等级：${baseResult.riskLevel}/10")
        appendLine("请只返回 JSON，不要 Markdown、不要概率、不要字段外内容。")
    }

    private fun contextWindow(text: String): String {
        if (text.length <= MAX_CONTEXT_LENGTH) return text
        val targetStart = text.lastIndexOf(CURRENT_MESSAGE_MARKER)
        if (targetStart < 0) return text.takeLast(MAX_CONTEXT_LENGTH)

        val target = text.substring(targetStart)
        if (target.length >= MAX_CONTEXT_LENGTH) {
            val targetText = target.removePrefix(CURRENT_MESSAGE_MARKER).trimStart()
            return "$CURRENT_MESSAGE_MARKER\n${targetText.takeLast(MAX_CONTEXT_LENGTH - CURRENT_MESSAGE_MARKER.length - 1)}"
        }

        val omitted = "…较早聊天已省略…\n"
        val recentContextLength = (MAX_CONTEXT_LENGTH - target.length - omitted.length).coerceAtLeast(0)
        val recentContext = text.substring(0, targetStart).takeLast(recentContextLength)
        return buildString(MAX_CONTEXT_LENGTH) {
            if (recentContext.isNotEmpty()) append(omitted).append(recentContext).append("\n\n")
            append(target)
        }
    }

    private fun AnalysisResult.withFallbackDetail(reason: String): AnalysisResult = copy(
        detailed = DetailedAnalysis(
            summary = "当前基础判断为：${emotion}，主要意图是${intents.firstOrNull()?.name ?: "未识别"}。",
            intention = reason,
            evidence = listOf("依据来自当前可见聊天文本的基础分类结果。"),
            action = suggestion,
            reply = "先回应对方这条消息的核心内容，再补充你的原因或下一步安排。",
            failureReason = reason,
        ),
    )

    private companion object {
        const val LOG_TAG = "JevDetailedIntent"
        const val MAX_CONTEXT_LENGTH = 4_000
        const val CURRENT_MESSAGE_MARKER = "当前待分析消息："
        const val SYSTEM_PROMPT = """
你是中文聊天关系理解模型。请基于聊天上下文，解释最后一条普通文本消息的真实沟通含义。
只输出 JSON，字段必须是：summary、intention、evidence、action、reply。
summary 是一句话概括；intention 用一句不超过50字的话直接说明对方这句话在上下文中的意思和诉求，不要只给意图类别；evidence 是 1 到 3 条来自原文的具体依据；action 是当前最合适的沟通动作；reply 是一条自然、克制、可直接发送的中文回复。
reply 必须综合最近的聊天上下文，以明确标记的“当前待分析消息”为回应依据，用“我”的口吻直接回应对方。不要只看孤立的一句话，不要回复更早的旧话题，不要写成沟通指导，不要凭空承诺时间、地点或行动。
不要输出概率、不要使用“可能是62%”等模糊分数，不要编造上下文中没有的事实，不要输出 Markdown。
"""
    }
}
