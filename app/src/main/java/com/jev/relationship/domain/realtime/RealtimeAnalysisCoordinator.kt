package com.jev.relationship.domain.realtime

import android.util.Log
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.domain.HistoryRepository
import com.jev.relationship.domain.inline.InlineAnalysisCardPublisher
import com.jev.relationship.domain.inline.InlineTextHasher
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.domain.source.VisibleChatConversation
import com.jev.relationship.domain.source.VisibleChatMessage
import com.jev.relationship.ipc.AnalysisOutputIpcMapper
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcAnalysisResultSink
import com.jev.relationship.ipc.MessageCaptureCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class RealtimeAnalysisCoordinator(
    private val captureCoordinator: MessageCaptureCoordinator,
    private val analyzer: ConversationAnalyzer,
    private val historyRepository: HistoryRepository,
    private val surfaceCoordinator: AssistantSurfaceCoordinator,
    private val settingsRepository: RealtimeAssistantSettingsRepository,
    private val scope: CoroutineScope,
    private val inlineCardPublisher: InlineAnalysisCardPublisher? = null,
    private val analysisResultSink: IpcAnalysisResultSink? = null,
    private val analysisResultCache: AnalysisResultCache? = null,
    private val localHistory: com.jev.relationship.domain.source.LocalConversationHistory? = null,
) {
    companion object {
        const val QUIET_WINDOW_MS = 700L
        private const val TAG = "JevRealtime"
    }

    private val _state = MutableStateFlow<RealtimeAnalysisState>(RealtimeAnalysisState.Disabled)
    private val quietJobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private val analysisJobs = mutableMapOf<String, Job>()
    private val regenerationJobs = mutableMapOf<String, Job>()
    private var collectorJob: Job? = null
    private var visibleConversationJob: Job? = null
    private var historySweepJob: Job? = null
    private var historySweepConversationKey: String? = null
    private var historySweepComplete = false
    private var historySweepMessageIds: Set<Long> = emptySet()
    private var historySweepReady: CompletableDeferred<Set<Long>>? = null
    private var visibleHistoryMessages: Map<Long, VisibleChatMessage> = emptyMap()
    private var queuedVisibleSnapshot: VisibleChatConversation? = null
    private var visibleConversationKey: String? = null
    private val visibleAnalyzedKeys = mutableSetOf<String>()
    private val visibleInFlightKeys = mutableSetOf<String>()
    private val disabledConversationKeys = mutableSetOf<String>()
    private var visibleGeneration = 0L
    private var enabled = false
    private val replyTargets = mutableMapOf<String, String>()
    private val latestReplies = mutableMapOf<String, com.jev.relationship.ipc.IpcReplySuggestion>()

    val state: StateFlow<RealtimeAnalysisState> = _state.asStateFlow()

    fun setEnabled(value: Boolean) {
        applyEnabled(value)
    }

    fun start() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            launch {
                settingsRepository.settings.collect { settings ->
                    logStatus("settings enabled=${settings.enabled}")
                    applyEnabled(settings.enabled)
                }
            }
            captureCoordinator.events.collect { message ->
                if (!enabled || !isConversationAnalysisEnabled(message.conversationId) || message.isOutgoing || message.sender == com.jev.relationship.ipc.MessageSender.SELF || !com.jev.relationship.ipc.ChatTextPolicy.isDialogue(message.text)) return@collect
                val conversationId = message.conversationId
                message.messageId?.let { selectReplyTarget(conversationId, it) }
                _state.value = RealtimeAnalysisState.Collecting(conversationId, message.messageId)
                quietJobs.remove(conversationId)?.cancel()
                val generation = (generations[conversationId] ?: 0L) + 1L
                generations[conversationId] = generation
                logStatus(
                    "message accepted conversation=${conversationHash(conversationId)} generation=$generation",
                )
                quietJobs[conversationId] = launchAnalysis(
                    conversation = captureCoordinator.conversationThrough(message) ?: return@collect,
                    conversationId = conversationId,
                    generation = generation,
                    messageId = message.messageId,
                    text = message.text,
                    isOutgoing = message.isOutgoing,
                )
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        applyEnabled(false)
    }

    fun submitVisibleConversation(snapshot: VisibleChatConversation) {
        if (!enabled || !isConversationAnalysisEnabled(snapshot.conversationKey)) return
        if (visibleConversationKey != snapshot.conversationKey) {
            visibleGeneration += 1L
            visibleConversationJob?.cancel()
            visibleConversationJob = null
            historySweepJob?.cancel()
            historySweepJob = null
            historySweepReady?.complete(emptySet())
            historySweepReady = CompletableDeferred()
            historySweepConversationKey = snapshot.conversationKey
            historySweepComplete = false
            visibleHistoryMessages = emptyMap()
            queuedVisibleSnapshot = null
            analysisResultSink?.clear()
            visibleAnalyzedKeys.clear()
            visibleInFlightKeys.clear()
            visibleConversationKey = snapshot.conversationKey
        }
        if (localHistory != null) {
            submitHistoryBackfillSnapshot(snapshot)
            return
        }
        val messages = snapshot.messages
            .filter { message -> !message.isOutgoing && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(message.text) }
            .ifEmpty {
            listOf(
                VisibleChatMessage(
                    text = snapshot.anchorText,
                    isOutgoing = snapshot.anchorIsOutgoing,
                    occurrence = 0,
                ),
            )
        }.filter { message -> !message.isOutgoing && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(message.text) }
        if (messages.isEmpty()) return
        val pendingMessages = messages.filter { message ->
            visibleMessageKey(message) !in visibleAnalyzedKeys &&
                visibleMessageKey(message) !in visibleInFlightKeys
        }
        if (pendingMessages.isEmpty()) return
        if (visibleConversationJob?.isActive == true) {
            queuedVisibleSnapshot = snapshot
            return
        }
        visibleGeneration += 1L
        val generation = visibleGeneration
        pendingMessages.forEach { visibleInFlightKeys += visibleMessageKey(it) }
        visibleConversationJob = scope.launch {
            try {
                delay(QUIET_WINDOW_MS)
                if (!isVisibleCurrent(generation)) return@launch
                val localHistoryMatch = localHistory?.let { provider ->
                    val history = provider.load("", snapshot.conversationKey)
                    val records = com.jev.relationship.domain.source.LocalHistoryContext
                        .matchVisibleRecords(history, snapshot.messages, snapshot.conversationKey.endsWith("@chatroom"))
                    records to com.jev.relationship.domain.source.LocalHistoryContext
                        .forVisible(history, snapshot.messages, snapshot.conversationKey.endsWith("@chatroom"))
                }
                val completeContexts = localHistoryMatch?.second
                pendingMessages.forEach { message ->
                    if (!isVisibleCurrent(generation)) return@launch
                    val analysisText = if (snapshot.messages.isEmpty()) {
                        snapshot.conversationText
                    } else {
                        val targetIndex = snapshot.messages.indexOf(message)
                        snapshot.messages.take(targetIndex + 1).joinToString("\n") {
                            (if (it.isOutgoing) "我：" else "对方：") + it.text
                        } + "\n\n当前待分析消息：" + message.text
                    }
                    val targetIndex = snapshot.messages.indexOf(message)
                    val conversation = completeContexts?.get(targetIndex) ?: Conversation(analysisText)
                    val localRecordId = localHistoryMatch?.first?.getOrNull(targetIndex)?.id
                    val messageId = visibleMessageId(snapshot.conversationKey, message, localRecordId)
                    val cached = analysisResultCache?.find(messageId)
                    if (!isVisibleCurrent(generation)) return@launch
                    if (cached != null) {
                        analysisResultSink?.publish(
                            cached.copy(
                                conversationHash = InlineTextHasher.hash(snapshot.conversationKey),
                                textHash = InlineTextHasher.hash(message.text),
                                isOutgoing = message.isOutgoing,
                                messageOccurrence = message.occurrence,
                            ),
                        )
                        visibleAnalyzedKeys += visibleMessageKey(message)
                        visibleInFlightKeys -= visibleMessageKey(message)
                        logStatus("visible cache hit conversation=${conversationHash(snapshot.conversationKey)}")
                        return@forEach
                    }
                    _state.value = RealtimeAnalysisState.Analyzing(
                        conversationId = snapshot.conversationKey,
                        generation = generation,
                        messageId = messageId,
                    )
                    logStatus(
                        "visible analysis started conversation=${conversationHash(snapshot.conversationKey)} generation=$generation",
                    )
                    val output = analyzer(conversation)
                    if (!isVisibleCurrent(generation)) return@launch

                    var historyId: Long? = null
                    var historyError: String? = null
                    try {
                        historyId = historyRepository.save(conversation, output)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        historyError = error.message ?: error::class.simpleName ?: "history save failed"
                    }
                    if (!isVisibleCurrent(generation)) return@launch

                    surfaceCoordinator.show(output.analysis)
                    _state.value = RealtimeAnalysisState.Completed(
                        conversationId = snapshot.conversationKey,
                        output = output,
                        historyId = historyId,
                        historyError = historyError,
                        messageId = messageId,
                    )
                    inlineCardPublisher?.publish(
                        messageId = messageId,
                        conversationId = snapshot.conversationKey,
                        text = message.text,
                        isOutgoing = message.isOutgoing,
                        output = output,
                        historyId = historyId,
                    )
                    val ipcResult = AnalysisOutputIpcMapper.toIpcResult(
                            messageId = messageId,
                            conversationId = snapshot.conversationKey,
                            text = message.text,
                            isOutgoing = message.isOutgoing,
                            messageOccurrence = message.occurrence,
                            output = output,
                            historyId = historyId,
                        )
                    analysisResultCache?.save(messageId, ipcResult)
                    if (!isVisibleCurrent(generation)) return@launch
                    analysisResultSink?.publish(ipcResult)
                    visibleAnalyzedKeys += visibleMessageKey(message)
                    visibleInFlightKeys -= visibleMessageKey(message)
                    logStatus(
                        "visible analysis completed conversation=${conversationHash(snapshot.conversationKey)} generation=$generation historySaved=${historyId != null}",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isVisibleCurrent(generation)) {
                    logStatus(
                        "visible analysis failed conversation=${conversationHash(snapshot.conversationKey)} generation=$generation type=${error::class.simpleName}",
                    )
                    _state.value = RealtimeAnalysisState.Error(
                        conversationId = snapshot.conversationKey,
                        message = error.message ?: error::class.simpleName ?: "analysis failed",
                        messageId = pendingMessages.lastOrNull()
                            ?.let { visibleMessageId(snapshot.conversationKey, it) },
                    )
                    pendingMessages.forEach { visibleInFlightKeys -= visibleMessageKey(it) }
                }
            } finally {
                if (visibleConversationJob === coroutineContext[Job]) {
                    pendingMessages.forEach { visibleInFlightKeys -= visibleMessageKey(it) }
                    visibleConversationJob = null
                    val queued = queuedVisibleSnapshot
                    queuedVisibleSnapshot = null
                    queued?.let(::submitVisibleConversation)
                }
            }
        }
    }

    fun leaveVisibleConversation() {
        if (visibleConversationKey == null) return
        visibleGeneration += 1L
        visibleConversationJob?.cancel()
        visibleConversationJob = null
        historySweepJob?.cancel()
        historySweepJob = null
        historySweepReady?.complete(emptySet())
        historySweepReady = null
        historySweepConversationKey = null
        historySweepComplete = false
        visibleHistoryMessages = emptyMap()
        queuedVisibleSnapshot = null
        visibleConversationKey = null
        visibleAnalyzedKeys.clear()
        visibleInFlightKeys.clear()
        analysisResultSink?.clear()
        inlineCardPublisher?.clear()
    }

    /**
     * Marks the current viewport stale after a real scroll while keeping cards
     * already attached to their message rows visible during refresh.
     */
    fun invalidateVisibleViewport() {
        if (visibleConversationKey == null) return
        visibleGeneration += 1L
        visibleConversationJob?.cancel()
        visibleConversationJob = null
        queuedVisibleSnapshot = null
        visibleAnalyzedKeys.clear()
        visibleInFlightKeys.clear()
    }

    fun invalidateLocalHistory() {
        cancelPendingWork()
        replyTargets.clear()
        latestReplies.clear()
        historySweepReady?.complete(emptySet())
        historySweepReady = null
        historySweepConversationKey = null
        historySweepComplete = false
        visibleHistoryMessages = emptyMap()
        analysisResultSink?.clear()
        inlineCardPublisher?.clear()
        surfaceCoordinator.hide()
    }

    fun setConversationAnalysisEnabled(conversationId: String, title: String, enabled: Boolean) {
        val keys = setOf(conversationId.trim(), title.trim()).filter(String::isNotEmpty)
        if (enabled) disabledConversationKeys.removeAll(keys) else disabledConversationKeys.addAll(keys)
        if (!enabled) {
            regenerationJobs[conversationId]?.cancel()
            quietJobs.remove(conversationId)?.cancel()
            generations[conversationId] = (generations[conversationId] ?: 0L) + 1L
            if (visibleConversationKey in keys) {
                visibleGeneration += 1L
                visibleConversationJob?.cancel()
                visibleConversationJob = null
                historySweepJob?.cancel()
                historySweepJob = null
                historySweepReady?.complete(emptySet())
                historySweepReady = null
                historySweepConversationKey = null
                historySweepComplete = false
                visibleHistoryMessages = emptyMap()
                queuedVisibleSnapshot = null
                visibleAnalyzedKeys.clear()
                visibleInFlightKeys.clear()
                analysisResultSink?.clear()
                inlineCardPublisher?.clear()
            }
        }
        logStatus("conversation analysis enabled=$enabled key=${conversationHash(conversationId)}")
    }

    private fun applyEnabled(value: Boolean) {
        val wasEnabled = enabled
        enabled = value
        if (!value) {
            cancelPendingWork()
            inlineCardPublisher?.clear()
            if (wasEnabled) analysisResultSink?.clear()
            surfaceCoordinator.hide()
            _state.value = RealtimeAnalysisState.Disabled
        } else if (collectorJob?.isActive != true) {
            _state.value = RealtimeAnalysisState.WaitingForPermission
        }
    }

    private fun cancelPendingWork() {
        regenerationJobs.values.toList().forEach(Job::cancel)
        quietJobs.values.forEach(Job::cancel)
        quietJobs.clear()
        generations.clear()
        visibleConversationJob?.cancel()
        visibleConversationJob = null
        historySweepJob?.cancel()
        historySweepJob = null
        historySweepReady?.complete(emptySet())
        historySweepReady = null
        historySweepConversationKey = null
        historySweepComplete = false
        visibleHistoryMessages = emptyMap()
        queuedVisibleSnapshot = null
        visibleConversationKey = null
        visibleAnalyzedKeys.clear()
        visibleInFlightKeys.clear()
        visibleGeneration += 1L
    }

    private fun submitHistoryBackfillSnapshot(snapshot: VisibleChatConversation) {
        val visible = snapshot.messages
            .asSequence()
            .filterNot { it.isOutgoing }
            .filter { com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it.text) }
            .mapNotNull { message -> message.localMessageId?.let { it to message } }
            .toMap()
        visibleHistoryMessages = visible
        queuedVisibleSnapshot = snapshot
        val conversationId = snapshot.conversationKey

        // The cache also restores cards after the module view host is recreated.
        scope.launch {
            if (!enabled || !isConversationAnalysisEnabled(conversationId) || visibleConversationKey != conversationId) return@launch
            latestReplies[conversationId]?.let { analysisResultSink?.publishReplySuggestion(it) }
            visible.values.forEach { message ->
                if (!enabled || !isConversationAnalysisEnabled(conversationId) || visibleConversationKey != conversationId) return@launch
                val messageId = "wechat-8.0.72-${message.localMessageId}"
                val cached = analysisResultCache?.find(messageId) ?: return@forEach
                if (enabled && isConversationAnalysisEnabled(conversationId) && visibleConversationKey == conversationId) {
                    analysisResultSink?.publish(cached.forVisibleMessage(conversationId, message))
                }
            }
        }

        if (regenerationJobs[conversationId]?.isActive == true) return

        if (historySweepConversationKey == conversationId &&
            (historySweepJob?.isActive == true ||
                (historySweepComplete && visible.keys.all { it in historySweepMessageIds }))) return
        historySweepConversationKey = conversationId
        historySweepComplete = false
        // Remember this viewport even if history is temporarily unavailable;
        // subsequent layouts must not turn a read failure into a retry loop.
        historySweepMessageIds = visible.keys.toSet()
        val sweepReady = historySweepReady?.takeIf { !it.isCompleted }
            ?: CompletableDeferred<Set<Long>>().also { historySweepReady = it }
        val sweepKey = conversationId
        historySweepJob = scope.launch {
            try {
                delay(QUIET_WINDOW_MS)
                if (!isHistorySweepCurrent(sweepKey)) return@launch
                // The authenticated WeChat side resolves the authoritative Chat_User itself;
                // passing the UI title preserves the same lookup contract as visible analysis.
                val history = checkNotNull(localHistory).load("", sweepKey)
                    .sortedWith(compareBy<com.jev.relationship.ipc.LocalChatRecord> { it.timestampMs }.thenBy { it.id })
                // Fill recent missing cards on entry; an older visible row gets its own
                // preceding context without triggering analysis of the entire archive.
                val recentStart = (history.size - com.jev.relationship.domain.source.LocalHistoryContext.CONTEXT_MESSAGE_LIMIT).coerceAtLeast(0)
                val latestIncoming = history.indexOfLast {
                    !it.isOutgoing && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it.text)
                }
                if (latestIncoming >= 0) selectReplyTarget(sweepKey, "wechat-8.0.72-${history[latestIncoming].id}")
                val targetIndices = history.indices.filter { index ->
                    index >= recentStart || index == latestIncoming || history[index].id in visibleHistoryMessages
                }
                val sweepMessageIds = targetIndices.asSequence().map(history::get)
                    .filterNot { it.isOutgoing }
                    .filter { com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it.text) }
                    .map { it.id }
                    .toSet()
                historySweepMessageIds = historySweepMessageIds + sweepMessageIds
                sweepReady.complete(sweepMessageIds)
                for (index in targetIndices.asReversed()) {
                    if (!isHistorySweepCurrent(sweepKey)) return@launch
                    val record = history[index]
                    if (record.isOutgoing || !com.jev.relationship.ipc.ChatTextPolicy.isDialogue(record.text)) continue
                    val messageId = "wechat-8.0.72-${record.id}"
                    val cached = analysisResultCache?.find(messageId)
                    if (cached != null) {
                        publishReply(sweepKey, cached)
                        visibleHistoryMessages[record.id]?.let { message ->
                            analysisResultSink?.publish(cached.forVisibleMessage(sweepKey, message))
                        }
                        continue
                    }

                    if (!reserveMessageAnalysis(messageId, checkNotNull(coroutineContext[Job]))) continue
                    try {
                        val conversation = com.jev.relationship.domain.source.LocalHistoryContext.forRecord(history, index)
                        val output = try {
                            _state.value = RealtimeAnalysisState.Analyzing(sweepKey, visibleGeneration, messageId)
                            analyzer(conversation)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            logStatus("history backfill failed conversation=${conversationHash(sweepKey)} type=${error::class.simpleName}")
                            continue
                        }
                        if (!isHistorySweepCurrent(sweepKey)) return@launch

                        if (output.analysis.detailed.failureReason != null) {
                            publishUnderstandingFailure(sweepKey, output)
                            return@launch
                        }

                        val historyId = try {
                            historyRepository.save(conversation, output)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            null
                        }
                        val ipcResult = AnalysisOutputIpcMapper.toIpcResult(
                            messageId = messageId,
                            conversationId = sweepKey,
                            text = record.text,
                            isOutgoing = false,
                            messageOccurrence = 0,
                            output = output,
                            historyId = historyId,
                        )
                        try {
                            analysisResultCache?.save(messageId, ipcResult)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            logStatus("history cache save failed conversation=${conversationHash(sweepKey)} type=${error::class.simpleName}")
                        }
                        if (!isHistorySweepCurrent(sweepKey)) return@launch
                        publishReply(sweepKey, ipcResult)
                        visibleHistoryMessages[record.id]?.let { message ->
                            analysisResultSink?.publish(ipcResult.forVisibleMessage(sweepKey, message))
                        }
                        _state.value = RealtimeAnalysisState.Completed(
                            conversationId = sweepKey,
                            output = output,
                            historyId = historyId,
                            historyError = null,
                            messageId = messageId,
                        )
                    } finally {
                        releaseMessageAnalysis(messageId, coroutineContext[Job])
                    }
                }
                logStatus("history backfill completed conversation=${conversationHash(sweepKey)} incoming=${sweepMessageIds.size}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isHistorySweepCurrent(sweepKey)) {
                    logStatus("history backfill unavailable conversation=${conversationHash(sweepKey)} type=${error::class.simpleName}")
                    _state.value = RealtimeAnalysisState.Error(
                        conversationId = sweepKey,
                        message = error.message ?: error::class.simpleName ?: "本地聊天记录读取失败",
                    )
                }
            } finally {
                sweepReady.complete(emptySet())
                if (historySweepConversationKey == sweepKey && historySweepJob === coroutineContext[Job]) {
                    historySweepComplete = true
                    historySweepJob = null
                    // A new row can appear while older rows are being analyzed.
                    // Re-read once for that row; cached messages never call the model.
                    queuedVisibleSnapshot?.takeIf {
                        it.conversationKey == sweepKey && isHistorySweepCurrent(sweepKey) &&
                            it.messages.any { message ->
                                !message.isOutgoing && message.localMessageId != null &&
                                    message.localMessageId !in historySweepMessageIds &&
                                    com.jev.relationship.ipc.ChatTextPolicy.isDialogue(message.text)
                            }
                    }?.let(::submitHistoryBackfillSnapshot)
                }
            }
        }
    }

    /** Explicit user action: keep old cache entries until a replacement succeeds. */
    fun regenerateRecentConversation(conversationId: String, onComplete: (String) -> Unit = {}): Boolean {
        if (conversationId.isBlank() || !enabled || !isConversationAnalysisEnabled(conversationId) || localHistory == null) {
            onComplete("请先开启解析并确认微信连接正常，原有缓存已保留")
            return false
        }
        if (regenerationJobs[conversationId]?.isActive == true) {
            onComplete("正在重新解读最近60条消息，请稍候")
            return false
        }
        if (historySweepConversationKey == conversationId) {
            historySweepJob?.cancel()
            historySweepJob = null
            historySweepReady?.complete(emptySet())
        }
        regenerationJobs[conversationId] = scope.launch {
            var succeeded = 0
            var failed = 0
            try {
                val history = localHistory.load("", conversationId)
                    .sortedWith(compareBy<com.jev.relationship.ipc.LocalChatRecord> { it.timestampMs }.thenBy { it.id })
                history.lastOrNull { !it.isOutgoing && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(it.text) }
                    ?.let { selectReplyTarget(conversationId, "wechat-8.0.72-${it.id}") }
                val targets = history.indices.toList().takeLast(com.jev.relationship.domain.source.LocalHistoryContext.CONTEXT_MESSAGE_LIMIT).filter {
                    !history[it].isOutgoing && com.jev.relationship.ipc.ChatTextPolicy.isDialogue(history[it].text)
                }
                // Suppress automatic rescans of this historical batch; newly received IDs still trigger analysis.
                if (historySweepConversationKey == conversationId) {
                    historySweepMessageIds = historySweepMessageIds + targets.map { history[it].id }
                    historySweepComplete = true
                }
                for (index in targets.asReversed()) {
                    val replaced = try {
                        regenerateRecord(history, index, conversationId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: UnderstandingUnavailableException) {
                        onComplete("已暂停重新生成：${error.message} 原有缓存已保留")
                        return@launch
                    } catch (_: Throwable) {
                        false
                    }
                    if (replaced) succeeded++ else failed++
                }
                onComplete(when {
                    targets.isEmpty() -> "最近60条消息中没有可解读的对方文本"
                    failed > 0 -> "已更新${succeeded}条解读，${failed}条未成功，原有缓存已保留"
                    else -> "已重新生成${succeeded}条气泡解读并缓存"
                })
            } catch (error: CancellationException) {
                onComplete("已停止重新生成，已完成的${succeeded}条和原有缓存均保留")
                throw error
            } catch (_: Throwable) {
                onComplete("重新生成失败，请检查连接后重试，原有缓存已保留")
            } finally {
                if (regenerationJobs[conversationId] === coroutineContext[Job]) regenerationJobs.remove(conversationId)
            }
        }
        return true
    }

    private suspend fun regenerateRecord(
        history: List<com.jev.relationship.ipc.LocalChatRecord>, index: Int, conversationId: String,
    ): Boolean {
        val record = history[index]
        val messageId = "wechat-8.0.72-${record.id}"
        analysisJobs[messageId]?.join()
        if (!enabled || !isConversationAnalysisEnabled(conversationId) || !coroutineContext.isActive) {
            throw CancellationException("Analysis disabled")
        }
        if (!reserveMessageAnalysis(messageId, checkNotNull(coroutineContext[Job]))) return false
        try {
            val conversation = com.jev.relationship.domain.source.LocalHistoryContext.forRecord(history, index)
            val output = analyzer(conversation)
            output.analysis.detailed.failureReason?.let { reason ->
                publishUnderstandingFailure(conversationId, output)
                throw UnderstandingUnavailableException(reason)
            }
            if (!output.analysis.detailed.contextual) return false
            if (!coroutineContext.isActive) throw CancellationException("Regeneration cancelled")
            val result = AnalysisOutputIpcMapper.toIpcResult(
                messageId = messageId, conversationId = conversationId, text = record.text,
                isOutgoing = false, output = output, historyId = null,
            )
            analysisResultCache?.save(messageId, result)
            publishReply(conversationId, result)
            if (visibleConversationKey == conversationId) {
                visibleHistoryMessages[record.id]?.let { analysisResultSink?.publish(result.forVisibleMessage(conversationId, it)) }
            }
            return true
        } finally {
            releaseMessageAnalysis(messageId, coroutineContext[Job])
        }
    }

    private fun IpcAnalysisResult.forVisibleMessage(
        conversationId: String,
        message: VisibleChatMessage,
    ): IpcAnalysisResult = copy(
        conversationHash = InlineTextHasher.hash(conversationId),
        textHash = InlineTextHasher.hash(message.text),
        isOutgoing = message.isOutgoing,
        messageOccurrence = message.occurrence,
    )

    /** A service failure is status, never a successful cached interpretation. */
    private suspend fun publishUnderstandingFailure(
        conversationId: String,
        output: com.jev.relationship.domain.AnalysisOutput,
    ) {
        if (visibleConversationKey != conversationId) return
        val reason = output.analysis.detailed.failureReason ?: return
        _state.value = RealtimeAnalysisState.Error(conversationId, reason)
        visibleHistoryMessages.values.forEach { message ->
            val messageId = "wechat-8.0.72-${message.localMessageId}"
            if (analysisResultCache?.find(messageId) == null) {
                analysisResultSink?.publish(AnalysisOutputIpcMapper.toIpcResult(
                    messageId = messageId, conversationId = conversationId, text = message.text,
                    isOutgoing = false, messageOccurrence = message.occurrence, output = output, historyId = null,
                ))
            }
        }
    }

    private class UnderstandingUnavailableException(reason: String) : IllegalStateException(reason)

    private fun selectReplyTarget(conversationId: String, messageId: String) {
        val previous = replyTargets[conversationId]
        if (previous == messageId) return
        val oldId = previous?.removePrefix("wechat-8.0.72-")?.toLongOrNull()
        val newId = messageId.removePrefix("wechat-8.0.72-").toLongOrNull()
        if (oldId != null && newId != null && newId < oldId) return
        replyTargets[conversationId] = messageId
        val cleared = com.jev.relationship.ipc.IpcReplySuggestion(InlineTextHasher.hash(conversationId), messageId, "")
        latestReplies[conversationId] = cleared
        analysisResultSink?.publishReplySuggestion(cleared)
    }

    private fun publishReply(conversationId: String, result: IpcAnalysisResult) {
        if (replyTargets[conversationId] != result.messageId || !result.detailContextual || result.isOutgoing) return
        val suggestion = com.jev.relationship.ipc.IpcReplySuggestion(
            InlineTextHasher.hash(conversationId), result.messageId, result.detailReply.trim(),
        )
        latestReplies[conversationId] = suggestion
        analysisResultSink?.publishReplySuggestion(suggestion)
    }

    private suspend fun isHistorySweepCurrent(conversationId: String): Boolean =
        enabled && coroutineContext.isActive && historySweepConversationKey == conversationId &&
            visibleConversationKey == conversationId && isConversationAnalysisEnabled(conversationId)

    private fun visibleMessageKey(message: VisibleChatMessage): String =
        "${InlineTextHasher.hash(message.text)}:${message.isOutgoing}:${message.occurrence}"

    private fun visibleMessageId(
        conversationId: String,
        message: VisibleChatMessage,
        localRecordId: Long? = null,
    ): String = localRecordId?.let {
        "wechat-8.0.72-$it"
    } ?: "accessibility-${InlineTextHasher.hash(conversationId).take(16)}-" +
        InlineTextHasher.hash(message.text).take(16) + "-${message.occurrence}"

    private fun launchAnalysis(
        conversation: Conversation,
        conversationId: String,
        generation: Long,
        messageId: String?,
        text: String,
        isOutgoing: Boolean,
    ): Job = scope.launch {
        try {
            delay(QUIET_WINDOW_MS)
            if (!isCurrent(conversationId, generation)) return@launch
            val capturedLocalId = messageId
                ?.takeIf { it.startsWith("wechat-8.0.72-") }
                ?.removePrefix("wechat-8.0.72-")
                ?.toLongOrNull()
            if (capturedLocalId != null && historySweepConversationKey == conversationId) {
                val coveredBySweep = historySweepReady?.await()?.contains(capturedLocalId) == true
                if (coveredBySweep && historySweepJob?.isActive == true) {
                    logStatus("captured message covered by history backfill conversation=${conversationHash(conversationId)}")
                    return@launch
                }
            }
            val cached = messageId?.let { analysisResultCache?.find(it) }
            if (!isCurrent(conversationId, generation)) return@launch
            if (cached != null) {
                publishReply(conversationId, cached)
                analysisResultSink?.publish(
                    cached.copy(
                        messageId = messageId.orEmpty(),
                        conversationHash = InlineTextHasher.hash(conversationId),
                        textHash = InlineTextHasher.hash(text),
                        isOutgoing = isOutgoing,
                        messageOccurrence = 0,
                    ),
                )
                logStatus("captured cache hit conversation=${conversationHash(conversationId)}")
                return@launch
            }
            if (messageId != null && !reserveMessageAnalysis(messageId, checkNotNull(coroutineContext[Job]))) return@launch
            _state.value = RealtimeAnalysisState.Analyzing(conversationId, generation, messageId)
            logStatus(
                "analysis started conversation=${conversationHash(conversationId)} generation=$generation",
            )
            val completeConversation = localHistory?.let { provider ->
                com.jev.relationship.domain.source.LocalHistoryContext.forCaptured(provider.load(conversationId, ""), messageId, text)
            } ?: conversation
            if (!isCurrent(conversationId, generation)) return@launch
            val output = analyzer(completeConversation)
            if (!isCurrent(conversationId, generation)) return@launch

            var historyId: Long? = null
            var historyError: String? = null
            try {
                historyId = historyRepository.save(completeConversation, output)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                historyError = error.message ?: error::class.simpleName ?: "history save failed"
            }
            if (!isCurrent(conversationId, generation)) return@launch

            surfaceCoordinator.show(output.analysis)
            _state.value = RealtimeAnalysisState.Completed(
                conversationId = conversationId,
                output = output,
                historyId = historyId,
                historyError = historyError,
                messageId = messageId,
            )
            messageId?.let {
                val ipcResult = AnalysisOutputIpcMapper.toIpcResult(
                    messageId = it,
                    conversationId = conversationId,
                    text = text,
                    isOutgoing = isOutgoing,
                    output = output,
                    historyId = historyId,
                )
                analysisResultCache?.save(it, ipcResult)
                if (!isCurrent(conversationId, generation)) return@launch
                inlineCardPublisher?.publish(
                    messageId = it,
                    conversationId = conversationId,
                    text = text,
                    isOutgoing = isOutgoing,
                    output = output,
                    historyId = historyId,
                )
                analysisResultSink?.publish(ipcResult)
                publishReply(conversationId, ipcResult)
            }
            logStatus(
                "analysis completed conversation=${conversationHash(conversationId)} generation=$generation historySaved=${historyId != null}",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(conversationId, generation)) {
                logStatus(
                    "analysis failed conversation=${conversationHash(conversationId)} generation=$generation type=${error::class.simpleName}",
                )
                _state.value = RealtimeAnalysisState.Error(
                    conversationId = conversationId,
                    message = error.message ?: error::class.simpleName ?: "analysis failed",
                    messageId = messageId,
                )
            }
        } finally {
            messageId?.let { releaseMessageAnalysis(it, coroutineContext[Job]) }
            if (quietJobs[conversationId] === coroutineContext[Job]) {
                quietJobs.remove(conversationId)
            }
        }
    }

    /** The live hook and visible-history scan share ownership until the result is saved. */
    private fun reserveMessageAnalysis(messageId: String, owner: Job): Boolean {
        if (analysisJobs[messageId]?.isActive == true) return false
        analysisJobs[messageId] = owner
        return true
    }

    private fun releaseMessageAnalysis(messageId: String, owner: Job?) {
        if (analysisJobs[messageId] === owner) analysisJobs.remove(messageId)
    }

    private suspend fun isCurrent(conversationId: String, generation: Long): Boolean =
        enabled && coroutineContext.isActive && generations[conversationId] == generation

    private suspend fun isVisibleCurrent(generation: Long): Boolean =
        enabled && coroutineContext.isActive && visibleGeneration == generation

    private fun logStatus(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun conversationHash(conversationId: String): String =
        Integer.toHexString(conversationId.hashCode())

    private fun isConversationAnalysisEnabled(conversationId: String): Boolean =
        conversationId.trim() !in disabledConversationKeys

}
