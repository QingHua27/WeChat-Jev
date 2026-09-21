package com.jev.relationship.domain

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.domain.realtime.ConversationAnalyzer
import javax.inject.Inject
import kotlinx.coroutines.flow.first

data class AnalysisOutput(
    val analysis: AnalysisResult,
    val replies: List<ReplySuggestion>,
)

class AnalysisConversationUseCase @Inject constructor(
    private val analyzer: JevAnalyzer,
    private val replyGenerator: ReplyGenerator,
    private val contactMemoryRepository: ContactMemoryRepository,
) : ConversationAnalyzer {
    override suspend operator fun invoke(conversation: Conversation): AnalysisOutput {
        val contact = conversation.contactId?.let { contactId ->
            contactMemoryRepository.observeContacts().first().firstOrNull { it.id == contactId }
        }
        val observations = conversation.contactId?.let { contactId ->
            contactMemoryRepository.observeMemory(contactId).first()
        }.orEmpty()
        val context = AnalysisContext(
            conversation = conversation,
            contact = contact,
            observations = observations,
        )
        val analysis = analyzer.analyze(context)
        val replies = replyGenerator.generate(context, analysis)
        return AnalysisOutput(analysis = analysis, replies = replies)
    }
}
