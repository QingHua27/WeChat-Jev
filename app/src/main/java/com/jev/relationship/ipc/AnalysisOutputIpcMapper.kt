package com.jev.relationship.ipc

import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.domain.inline.InlineTextHasher

object AnalysisOutputIpcMapper {
    fun toIpcResult(
        messageId: String,
        conversationId: String,
        text: String,
        isOutgoing: Boolean,
        messageOccurrence: Int = 0,
        output: AnalysisOutput,
        historyId: Long?,
    ): IpcAnalysisResult = IpcAnalysisResult(
        messageId = messageId,
        conversationHash = InlineTextHasher.hash(conversationId),
        textHash = InlineTextHasher.hash(text),
        isOutgoing = isOutgoing,
        messageOccurrence = messageOccurrence,
        emotion = output.analysis.emotion.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        intents = output.analysis.intents
            .asSequence()
            .filter { it.name.isNotBlank() }
            .take(IpcProtocol.MAX_ANALYSIS_INTENTS)
            .map { intent ->
                IpcIntentProbability(
                    name = intent.name.take(IpcProtocol.MAX_INTENT_NAME_LENGTH),
                    confidence = intent.confidence.coerceIn(0.0, 1.0),
                )
            }
            .toList(),
        riskLevel = output.analysis.riskLevel.coerceIn(0, 10),
        suggestion = output.analysis.suggestion.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        detailSummary = output.analysis.detailed.summary.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        detailIntention = output.analysis.detailed.intention.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        detailContextual = output.analysis.detailed.contextual,
        detailEvidence = output.analysis.detailed.evidence
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(IpcProtocol.MAX_DETAIL_EVIDENCE)
            .map { it.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH) }
            .toList(),
        detailAction = output.analysis.detailed.action.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        detailReply = output.analysis.detailed.reply.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        historyId = historyId,
        sections = output.analysis.sections.take(IpcProtocol.MAX_ANALYSIS_SECTIONS).map { section ->
            IpcAnalysisSection(
                title = section.title.take(IpcProtocol.MAX_INTENT_NAME_LENGTH),
                options = section.options.take(IpcProtocol.MAX_ANALYSIS_INTENTS).map {
                    IpcIntentProbability(it.name.take(IpcProtocol.MAX_INTENT_NAME_LENGTH), it.confidence)
                },
                text = section.text.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
            )
        },
    )
}
