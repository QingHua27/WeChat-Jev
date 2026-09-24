package com.jev.relationship.data.remote

import com.jev.relationship.domain.AnalysisContext
import com.jev.relationship.domain.memoryPrompt

object RemotePromptBuilder {
    fun replyUserPrompt(context: AnalysisContext, suggestion: String): String = buildString {
        append("聊天内容：\n")
        val text = context.conversation.text
        if (text.length > 6_000) append("[较早聊天已省略；请仅依据以下片段生成回复]\n")
        append(text.takeLast(6_000))
        append("\n分析建议：\n")
        append(suggestion)
        append("\n\n")
        append(context.memoryPrompt())
    }
}
