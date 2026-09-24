package com.jev.relationship.domain.chatassistant

import com.jev.relationship.data.remote.ChatAssistantCompletion
import com.jev.relationship.data.remote.ChatMessage
import com.jev.relationship.data.settings.OpenAiProviderSettings
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.domain.source.LocalConversationHistory
import com.jev.relationship.ipc.ChatAssistantRequest
import com.jev.relationship.ipc.ChatAssistantTurn
import com.jev.relationship.ipc.LocalChatRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAssistantCoordinatorTest {
    @Test
    fun `explicit full analysis restores omitted context without clearing saved exchanges`() = runBlocking {
        val sent = mutableListOf<List<ChatMessage>>()
        val store = MemoryAssistantStore()
        val history = (1L..200L).map { LocalChatRecord(it, "原始记录$it:" + "历史".repeat(100), it, false) }
        val coordinator = coordinator(
            history = LocalConversationHistory { _, _ -> history }, store = store,
            complete = ChatAssistantCompletion { _, messages -> sent += messages; "本次回答" })
        coordinator.handle(ChatAssistantRequest("saving", "alice", "Alice"))
        assertTrue(!sent.last()[1].content.contains("原始记录1:"))
        coordinator.handle(ChatAssistantRequest("full", "alice", "Alice", fullContext = true))
        assertTrue(sent.last()[1].content.contains("原始记录1:"))
        assertTrue(sent.last()[1].content.contains("原始记录200:"))
        assertEquals(ChatAssistantPromptBuilder.REPORT_REQUEST, sent.last().last().content)
        assertEquals(4, store.load("alice")!!.turns.size)
    }

    @Test
    fun `streams provisional text but persists only the completed exchange`() = runBlocking {
        val store = MemoryAssistantStore()
        val partials = mutableListOf<com.jev.relationship.ipc.ChatAssistantResult>()
        val provider = object : ChatAssistantCompletion {
            override suspend fun complete(settings: OpenAiProviderSettings, messages: List<ChatMessage>) = "completed answer"
            override suspend fun stream(settings: OpenAiProviderSettings, messages: List<ChatMessage>, onPartial: (String) -> Unit): String {
                onPartial("first")
                assertEquals(null, store.load("alice"))
                onPartial("first second")
                assertEquals(null, store.load("alice"))
                return "completed answer"
            }
        }
        val result = coordinator(LocalConversationHistory { _, _ -> listOf(LocalChatRecord(1, "chat", 1L, false)) }, store, provider)
            .handle(ChatAssistantRequest("r", "alice", "Alice"), partials::add)
        assertEquals(listOf("first", "first second"), partials.map { it.turns.single().content })
        assertTrue(partials.all { it.isPartial && it.requestId == "r" && it.conversationId == "alice" })
        assertEquals(false, result.isPartial)
        assertEquals("completed answer", store.load("alice")!!.turns.last().content)
    }

    @Test
    fun `failed stream does not save its provisional answer`() = runBlocking {
        val store = MemoryAssistantStore()
        val partials = mutableListOf<com.jev.relationship.ipc.ChatAssistantResult>()
        val provider = object : ChatAssistantCompletion {
            override suspend fun complete(settings: OpenAiProviderSettings, messages: List<ChatMessage>): String = throw java.io.IOException("disconnected")
            override suspend fun stream(settings: OpenAiProviderSettings, messages: List<ChatMessage>, onPartial: (String) -> Unit): String {
                onPartial("unfinished")
                throw java.io.IOException("disconnected")
            }
        }
        val failure = runCatching {
            coordinator(LocalConversationHistory { _, _ -> listOf(LocalChatRecord(1, "chat", 1L, false)) }, store, provider)
                .handle(ChatAssistantRequest("r", "alice", "Alice"), partials::add)
        }.exceptionOrNull()
        assertEquals("unfinished", partials.single().turns.single().content)
        assertEquals(ChatAssistantFailure.Stage.MODEL_REQUEST, (failure as ChatAssistantFailure).stage)
        assertEquals(null, store.load("alice"))
    }

    @Test
    fun `reopening restores saved exchanges without reading WeChat or calling the model`() = runBlocking {
        val store = MemoryAssistantStore().apply {
            saveReport("alice", "Alice", "分析", "上次分析")
            appendExchange("alice", "Alice", "上次问题", "上次回答")
            saveReport("bob", "Bob", "分析", "另一个聊天")
        }
        val expected = store.load("alice")!!.turns.map { it.content }
        val result = coordinator(
            history = LocalConversationHistory { _, _ -> error("must not read WeChat when restoring") },
            store = store,
            complete = ChatAssistantCompletion { _, _ -> error("must not call provider when restoring") },
        ).handle(ChatAssistantRequest("resume", "alice", "Alice", resumeSession = true))

        assertEquals(expected, result.turns.map { it.content })
        assertEquals(expected, store.load("alice")!!.turns.map { it.content })
        assertEquals(null, result.error)
    }

    @Test
    fun `first open analyzes once and follow-up after reopening includes saved context and fresh chat`() = runBlocking {
        val store = MemoryAssistantStore()
        var historyReads = 0
        val sent = mutableListOf<List<ChatMessage>>()
        val coordinator = coordinator(
            history = LocalConversationHistory { _, _ ->
                historyReads++
                listOf(LocalChatRecord(1, "最新聊天 $historyReads", 1L, false))
            },
            store = store,
            complete = ChatAssistantCompletion { _, messages -> sent += messages; "回答 ${sent.size}" },
        )
        coordinator.handle(ChatAssistantRequest("first", "alice", "Alice", resumeSession = true))
        coordinator.handle(ChatAssistantRequest("resume", "alice", "Alice", resumeSession = true))
        assertEquals(1, historyReads)
        assertEquals(1, sent.size)
        val answer = coordinator.handle(ChatAssistantRequest("ask", "alice", "Alice", "接着说"))
        assertEquals(2, historyReads)
        assertTrue(sent.last().any { it.content == "回答 1" })
        assertTrue(sent.last()[1].content.contains("最新聊天 2"))
        assertEquals("接着说", sent.last().last().content)
        assertEquals("回答 2", answer.turns.last().content)
        coordinator.handle(ChatAssistantRequest("refresh", "alice", "Alice"))
        assertEquals(3, sent.size)
    }

    @Test
    fun `combined history and model work returns timeout before the client deadline`() = runTest {
        val store = MemoryAssistantStore()
        val result = withTimeout(180_000) {
            coordinator(
                history = LocalConversationHistory { _, _ ->
                    kotlinx.coroutines.delay(30_000)
                    listOf(LocalChatRecord(1, "test", 1L, false))
                },
                store = store,
                complete = ChatAssistantCompletion { _, _ ->
                    kotlinx.coroutines.delay(160_000)
                    "too late"
                },
            ).handle(ChatAssistantRequest("slow", "alice", "Alice"))
        }

        assertEquals("slow", result.requestId)
        assertEquals("alice", result.conversationId)
        assertTrue(result.error.orEmpty().contains("超时"))
        assertEquals(null, store.load("alice"))
    }

    @Test
    fun `parent timeout still cancels work instead of becoming a provider failure`() = runTest {
        val failure = runCatching {
            withTimeout(10) {
                coordinator(
                    history = LocalConversationHistory { _, _ -> awaitCancellation() },
                    store = MemoryAssistantStore(),
                    complete = ChatAssistantCompletion { _, _ -> "unused" },
                ).handle(ChatAssistantRequest("cancel", "alice", "Alice"))
            }
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test
    fun `history timeout returns a classified failure instead of silently cancelling the request`() = runTest {
        val failure = runCatching {
            coordinator(
                history = LocalConversationHistory { _, _ -> withTimeout(10) { awaitCancellation() } },
                store = MemoryAssistantStore(),
                complete = ChatAssistantCompletion { _, _ -> error("must not request model") },
            ).handle(ChatAssistantRequest("open", "alice", "Alice"))
        }.exceptionOrNull()

        assertTrue("History timeout must reach the IPC error response: $failure", failure is ChatAssistantFailure)
        assertEquals(ChatAssistantFailure.Stage.LOCAL_HISTORY, (failure as ChatAssistantFailure).stage)
    }

    @Test
    fun `each request loads fresh transcript and reuses same chat turns`() = runBlocking {
        var history = listOf(LocalChatRecord(1, "你周五有空吗", 10L, false))
        val store = MemoryAssistantStore()
        val sent = mutableListOf<Pair<OpenAiProviderSettings, List<ChatMessage>>>()
        val answers = ArrayDeque(listOf("她在确认时间。", "看起来是在讨论周五安排。", "你可以先确认具体时间。", "群聊正在确定时间。"))
        val coordinator = coordinator(
            history = LocalConversationHistory { _, _ -> history },
            store = store,
            complete = ChatAssistantCompletion { settings, messages ->
                sent += settings to messages
                answers.removeFirst()
            },
        )

        coordinator.handle(ChatAssistantRequest("open-1", "alice", "Alice"))
        history = history + LocalChatRecord(2, "我想约你吃饭", 20L, true)
        coordinator.handle(ChatAssistantRequest("open-2", "alice", "Alice"))
        val response = coordinator.handle(ChatAssistantRequest("ask-1", "alice", "Alice", "她是在约我吗？"))

        assertTrue(sent[0].second[1].content.contains("你周五有空吗"))
        assertTrue(sent[1].second[1].content.contains("我想约你吃饭"))
        assertEquals("她在确认时间。", sent[1].second[3].content)
        assertEquals("她是在约我吗？", sent[2].second.last().content)
        assertEquals("understanding-model", sent[2].first.model)
        assertEquals("ask-1", response.requestId)
        assertEquals(listOf(ChatAssistantRequest.SAVING_ANALYSIS_PROMPT, "她在确认时间。", ChatAssistantRequest.SAVING_ANALYSIS_PROMPT, "看起来是在讨论周五安排。", "她是在约我吗？", "你可以先确认具体时间。"), response.turns.map { it.content })
        assertEquals("room@chatroom", coordinator.handle(ChatAssistantRequest("group", "room@chatroom", "Friends")).conversationId)
    }

    @Test
    fun `provider failure does not persist a half follow-up exchange`() = runBlocking {
        val store = MemoryAssistantStore().apply {
            saveReport("alice", "Alice", ChatAssistantPromptBuilder.REPORT_REQUEST, "她想约时间。")
        }
        val coordinator = coordinator(
            history = LocalConversationHistory { _, _ -> listOf(LocalChatRecord(1, "周六见吗", 1L, false)) },
            store = store,
            complete = ChatAssistantCompletion { _, _ -> error("provider unavailable") },
        )

        val failure = runCatching { coordinator.handle(ChatAssistantRequest("ask", "alice", "Alice", "几点比较好？")) }.exceptionOrNull()

        assertEquals(ChatAssistantFailure.Stage.MODEL_REQUEST, (failure as ChatAssistantFailure).stage)
        assertEquals(listOf(ChatAssistantPromptBuilder.REPORT_REQUEST, "她想约时间。"), store.load("alice")?.turns?.map { it.content })
        assertFalse(store.load("alice")?.turns?.orEmpty()?.any { it.content == "几点比较好？" } == true)
    }

    @Test
    fun `history failure is classified without exposing transcript text`() = runBlocking {
        val failure = runCatching {
            coordinator(
                history = LocalConversationHistory { _, _ -> error("database locked") },
                store = MemoryAssistantStore(),
                complete = ChatAssistantCompletion { _, _ -> "unused" },
            ).handle(ChatAssistantRequest("open", "alice", "Alice"))
        }.exceptionOrNull()

        assertEquals(ChatAssistantFailure.Stage.LOCAL_HISTORY, (failure as ChatAssistantFailure).stage)
        assertEquals("database locked", failure.cause?.message)
    }

    @Test
    fun `oversized provider answer is rejected before persisting the exchange`() = runBlocking {
        val store = MemoryAssistantStore().apply {
            saveReport("alice", "Alice", ChatAssistantPromptBuilder.REPORT_REQUEST, "她想约时间。")
        }
        val coordinator = coordinator(
            history = LocalConversationHistory { _, _ -> listOf(LocalChatRecord(1, "周六见吗", 1L, false)) },
            store = store,
            complete = ChatAssistantCompletion { _, _ -> "x".repeat(ChatAssistantTurn.MAX_CONTENT_LENGTH + 1) },
        )

        runCatching { coordinator.handle(ChatAssistantRequest("ask", "alice", "Alice", "几点比较好？")) }

        assertEquals(listOf(ChatAssistantPromptBuilder.REPORT_REQUEST, "她想约时间。"), store.load("alice")?.turns?.map { it.content })
    }

    @Test
    fun `cancelling an in flight report prevents it from being persisted`() = runBlocking {
        val store = MemoryAssistantStore()
        val providerEntered = CompletableDeferred<Unit>()
        val provider = CompletableDeferred<String>()
        val coordinator = coordinator(
            history = LocalConversationHistory { _, _ -> listOf(LocalChatRecord(1, "周六见吗", 1L, false)) },
            store = store,
            complete = ChatAssistantCompletion { _, _ ->
                providerEntered.complete(Unit)
                provider.await()
            },
        )

        val request = launch { coordinator.handle(ChatAssistantRequest("open", "alice", "Alice")) }
        providerEntered.await()
        request.cancelAndJoin()

        assertEquals(null, store.load("alice"))
    }

    private fun coordinator(
        history: LocalConversationHistory,
        store: MemoryAssistantStore,
        complete: ChatAssistantCompletion,
    ) = ChatAssistantCoordinator(
        localHistory = history,
        store = store,
        settingsRepository = TestSettingsRepository(),
        completion = complete,
    )

    private class TestSettingsRepository : SettingsRepository {
        override val providerSettings = MutableStateFlow(ProviderSettings())
        override suspend fun currentProviderSettings() = ProviderSettings(
            replyBaseUrl = "https://understanding.example/v1/",
            replyApiKey = "secret",
            replyModel = "understanding-model",
        )
        override suspend fun saveProviderSettings(settings: ProviderSettings) = Unit
    }

    private class MemoryAssistantStore : ChatAssistantStore {
        private val sessions = mutableMapOf<String, MutableList<ChatAssistantConversationTurn>>()
        private val titles = mutableMapOf<String, String>()

        override suspend fun load(conversationId: String): ChatAssistantSession? {
            val turns = sessions[conversationId] ?: return null
            return ChatAssistantSession(conversationId, titles.getValue(conversationId), 1L, 100L, turns.toList())
        }

        override suspend fun saveReport(conversationId: String, title: String, question: String, assistantText: String) {
            titles[conversationId] = title
            sessions.getOrPut(conversationId) { mutableListOf() }.apply {
                add(ChatAssistantConversationTurn("user", question, 100L))
                add(ChatAssistantConversationTurn("assistant", assistantText, 100L))
            }
        }

        override suspend fun appendExchange(conversationId: String, title: String, question: String, answer: String) {
            titles[conversationId] = title
            sessions.getOrPut(conversationId) { mutableListOf() }.apply {
                add(ChatAssistantConversationTurn("user", question, 100L))
                add(ChatAssistantConversationTurn("assistant", answer, 100L))
            }
        }
    }
}
