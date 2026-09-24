package com.jev.relationship.domain

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.MemoryKind
import com.jev.relationship.core.model.MemoryObservation
import com.jev.relationship.core.model.ReplySuggestion
import com.jev.relationship.core.model.ReplyTone
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisConversationUseCaseTest {
    @Test
    fun failedUnderstandingDoesNotMakeAnotherReplyRequest() = runTest {
        val analyzer = RecordingAnalyzer(AnalysisResult("平静", emptyList(), 0, "", detailed =
            com.jev.relationship.core.model.DetailedAnalysis(failureReason = "HTTP 429")))
        val generator = RecordingReplyGenerator(emptyList())
        AnalysisConversationUseCase(analyzer, generator, EmptyMemoryRepository)(Conversation("你好"))
        assertEquals(null, generator.receivedConversation)
    }

    @Test
    fun resolvedAnalysisDoesNotGenerateAnotherReply() = runTest {
        val analyzer = RecordingAnalyzer(AnalysisResult("平静", emptyList(), 0, "停止", stopAfterAnalysis = true))
        val generator = RecordingReplyGenerator(emptyList())
        val output = AnalysisConversationUseCase(analyzer, generator, EmptyMemoryRepository)(Conversation("这还差不多。"))
        assertEquals(null, generator.receivedConversation)
        assertEquals(emptyList<ReplySuggestion>(), output.replies)
    }
    @Test
    fun analysisAndRepliesUseTheSameConversation() = runTest {
        val conversation = Conversation("你：今天怎么没找我？\n她：忙")
        val expectedAnalysis = AnalysisResult(
            emotion = "不满",
            intents = listOf(IntentProbability("希望被关注", 0.72)),
            riskLevel = 8,
            suggestion = "先回应情绪",
        )
        val expectedReplies = listOf(ReplySuggestion(ReplyTone.Gentle, "我在听，你愿意和我说说吗？"))
        val analyzer = RecordingAnalyzer(expectedAnalysis)
        val generator = RecordingReplyGenerator(expectedReplies)

        val output = AnalysisConversationUseCase(analyzer, generator, EmptyMemoryRepository)(conversation)

        assertEquals(conversation, analyzer.receivedConversation)
        assertEquals(conversation, generator.receivedConversation)
        assertEquals(expectedAnalysis, output.analysis)
        assertEquals(expectedReplies, output.replies)
    }

    @Test
    fun selectedContactMemoryIsPassedToBothProviders() = runTest {
        val conversation = Conversation("她：最近有点累", contactId = "contact-1")
        val memory = listOf(MemoryObservation(1L, "contact-1", MemoryKind.UserNote, "不喜欢长解释", 1.0, 1L))
        val analyzer = ContextRecordingAnalyzer()
        val generator = ContextRecordingReplyGenerator()

        AnalysisConversationUseCase(analyzer, generator, MemoryRepository(memory))(conversation)

        assertEquals(memory, analyzer.receivedContext?.observations)
        assertEquals(memory, generator.receivedContext?.observations)
    }

    private object EmptyMemoryRepository : ContactMemoryRepository {
        override fun observeContacts(): Flow<List<Contact>> = flowOf(emptyList())
        override suspend fun saveContact(contact: Contact) = Unit
        override suspend fun deleteContact(contactId: String) = Unit
        override fun observeMemory(contactId: String): Flow<List<MemoryObservation>> = flowOf(emptyList())
        override suspend fun saveObservation(observation: MemoryObservation): Long = 1L
        override suspend fun deleteObservation(observationId: Long) = Unit
    }

    private class MemoryRepository(
        private val memory: List<MemoryObservation>,
    ) : ContactMemoryRepository by EmptyMemoryRepository {
        override fun observeMemory(contactId: String): Flow<List<MemoryObservation>> = flowOf(memory)
    }

    private class RecordingAnalyzer(
        private val result: AnalysisResult,
    ) : JevAnalyzer {
        var receivedConversation: Conversation? = null

        override suspend fun analyze(conversation: Conversation): AnalysisResult {
            receivedConversation = conversation
            return result
        }
    }

    private class ContextRecordingAnalyzer : JevAnalyzer {
        var receivedContext: AnalysisContext? = null

        override suspend fun analyze(conversation: Conversation): AnalysisResult = expectedAnalysis()

        override suspend fun analyze(context: AnalysisContext): AnalysisResult {
            receivedContext = context
            return expectedAnalysis()
        }

        private fun expectedAnalysis() = AnalysisResult("平静", emptyList(), 1, "先倾听")
    }

    private class RecordingReplyGenerator(
        private val replies: List<ReplySuggestion>,
    ) : ReplyGenerator {
        var receivedConversation: Conversation? = null

        override suspend fun generate(
            conversation: Conversation,
            analysis: AnalysisResult,
        ): List<ReplySuggestion> {
            receivedConversation = conversation
            return replies
        }
    }

    private class ContextRecordingReplyGenerator : ReplyGenerator {
        var receivedContext: AnalysisContext? = null

        override suspend fun generate(conversation: Conversation, analysis: AnalysisResult): List<ReplySuggestion> = emptyList()

        override suspend fun generate(context: AnalysisContext, analysis: AnalysisResult): List<ReplySuggestion> {
            receivedContext = context
            return emptyList()
        }
    }
}
