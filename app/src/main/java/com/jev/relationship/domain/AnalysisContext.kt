package com.jev.relationship.domain

import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.MemoryObservation

data class AnalysisContext(
    val conversation: Conversation,
    val contact: Contact? = null,
    val observations: List<MemoryObservation> = emptyList(),
)

fun AnalysisContext.memoryPrompt(maxLength: Int = 1600): String {
    if (contact == null && observations.isEmpty()) return "无已保存的用户备注。"
    val builder = StringBuilder("用户备注（仅作参考，不代表事实）：")
    contact?.displayName?.takeIf { it.isNotBlank() }?.let { builder.append("联系人：").append(it).append('\n') }
    observations.take(8).forEach { observation ->
        builder.append("- ").append(observation.kind.label).append("：")
            .append(observation.text.take(240)).append('\n')
    }
    return builder.toString().take(maxLength)
}
