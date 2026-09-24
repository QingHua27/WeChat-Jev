package com.jev.relationship.ipc

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.jev.relationship.data.settings.XposedIntegrationRepository
import com.jev.relationship.data.settings.XposedIntegrationSettings
import com.jev.relationship.domain.chatassistant.ChatAssistantCoordinator
import com.jev.relationship.domain.chatassistant.ChatAssistantFailure
import com.jev.relationship.domain.chatassistant.ChatAssistantErrorMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class JevIpcService : Service() {
    @Inject lateinit var integrationRepository: XposedIntegrationRepository
    @Inject lateinit var coordinator: MessageCaptureCoordinator
    @Inject lateinit var analysisResultBroadcaster: IpcAnalysisResultBroadcaster
    @Inject lateinit var localHistoryBroker: LocalHistoryBroker
    @Inject lateinit var realtimeCoordinator: com.jev.relationship.domain.realtime.RealtimeAnalysisCoordinator
    @Inject lateinit var chatAssistantCoordinator: ChatAssistantCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latestSettings = XposedIntegrationSettings()
    private val settingsReadiness = IpcSettingsReadiness()
    private var authenticatedClientBinder: IBinder? = null
    private data class ActiveAssistantRequest(val conversationId: String, val job: Job)
    private val assistantRequests = mutableMapOf<String, ActiveAssistantRequest>()
    private val assistantRequestByConversation = mutableMapOf<String, String>()
    private val assistantForeground by lazy { ChatAssistantForegroundSession(this) }
    private val session by lazy {
        IpcSession(
            settingsProvider = { latestSettings },
            coordinator = coordinator,
        )
    }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                IpcProtocol.MSG_HELLO -> handleHello(message)
                IpcProtocol.MSG_SUBMIT -> handleSubmit(message)
                IpcProtocol.MSG_DISCONNECT -> handleDisconnect(message)
                IpcProtocol.MSG_HISTORY_PAGE -> if (isAuthenticated(message)) {
                    runCatching { LocalHistoryPage.fromBundle(message.data) }.onSuccess(localHistoryBroker::receive)
                }
                IpcProtocol.MSG_HISTORY_INVALIDATED -> if (isAuthenticated(message)) {
                    realtimeCoordinator.invalidateLocalHistory()
                }
                IpcProtocol.MSG_CONVERSATION_TOGGLE -> if (isAuthenticated(message)) {
                    realtimeCoordinator.setConversationAnalysisEnabled(
                        conversationId = message.data.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
                        title = message.data.getString(IpcProtocol.KEY_CONVERSATION_TITLE).orEmpty(),
                        enabled = message.data.getBoolean(IpcProtocol.KEY_ANALYSIS_ENABLED, true),
                    )
                }
                IpcProtocol.MSG_VISIBLE_CHAT -> if (isAuthenticated(message)) {
                    runCatching { IpcVisibleChatConversation.fromBundle(message.data) }
                        .onSuccess { visible ->
                            realtimeCoordinator.submitVisibleConversation(
                                com.jev.relationship.domain.source.VisibleChatConversation(
                                    conversationKey = visible.conversationId,
                                    conversationText = visible.conversationText,
                                    anchorText = visible.anchorText,
                                    anchorIsOutgoing = visible.anchorIsOutgoing,
                                    messages = visible.messages.map {
                                        com.jev.relationship.domain.source.VisibleChatMessage(
                                            text = it.text,
                                            isOutgoing = it.isOutgoing,
                                            occurrence = it.occurrence,
                                            localMessageId = it.localMessageId,
                                        )
                                    },
                                ),
                            )
                        }
                }
                IpcProtocol.MSG_CHAT_ASSISTANT_REQUEST -> if (isAuthenticated(message)) {
                    handleChatAssistantRequest(message)
                }
                IpcProtocol.MSG_REGENERATE_RECENT -> if (isAuthenticated(message)) {
                    val conversationId = message.data.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty()
                    val replyTo = message.replyTo
                    if (conversationId.isNotBlank() && conversationId.length <= IpcProtocol.MAX_MESSAGE_ID_LENGTH && replyTo != null) {
                        realtimeCoordinator.regenerateRecentConversation(conversationId) { status ->
                            if (replyTo.binder == authenticatedClientBinder) send(replyTo, IpcProtocol.MSG_REGENERATE_STATUS,
                                android.os.Bundle().apply {
                                    putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId)
                                    putString(IpcProtocol.KEY_REASON, status)
                                })
                        }
                    }
                }
                IpcProtocol.MSG_CHAT_ASSISTANT_CANCEL -> if (isAuthenticated(message)) {
                    cancelChatAssistantRequest(message.data.getString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID).orEmpty())
                }
                else -> super.handleMessage(message)
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "IPC service created")
        // The authenticated Xposed bridge is the primary message source; it must
        // keep working when Android has not rebound the optional accessibility service.
        realtimeCoordinator.start()
        serviceScope.launch {
            integrationRepository.settings.collectLatest { settings ->
                latestSettings = settings
                settingsReadiness.publish(settings)
                if (!settings.enabled || !settings.isPaired) {
                    cancelAllChatAssistantRequests()
                    authenticatedClientBinder = null
                    analysisResultBroadcaster.clearClient()
                    localHistoryBroker.disconnect()
                    session.disconnect()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == IpcProtocol.SERVICE_ACTION) {
            Log.i(TAG, "IPC bind accepted")
            messenger.binder
        } else null

    override fun onUnbind(intent: Intent?): Boolean {
        cancelAllChatAssistantRequests()
        localHistoryBroker.disconnect()
        analysisResultBroadcaster.clearClient()
        realtimeCoordinator.invalidateVisibleViewport()
        session.disconnect()
        authenticatedClientBinder = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelAllChatAssistantRequests()
        localHistoryBroker.disconnect()
        analysisResultBroadcaster.clearClient()
        realtimeCoordinator.invalidateVisibleViewport()
        session.disconnect()
        authenticatedClientBinder = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleHello(message: Message) {
        val replyTo = message.replyTo ?: return
        val hello = IpcHello.fromBundle(message.data)
        val callerPackages = packageManager.getPackagesForUid(message.sendingUid).orEmpty().toSet()
        serviceScope.launch {
            settingsReadiness.awaitInitial()
            Handler(Looper.getMainLooper()).post {
                val result = session.handshake(hello, callerPackages)
                Log.i(
                    TAG,
                    "IPC handshake accepted=${result.accepted} reason=${result.reason} " +
                        "callerHasWechat=${callerPackages.contains(IpcProtocol.WECHAT_PACKAGE)}",
                )
                authenticatedClientBinder = if (result.accepted) replyTo.binder else null
                send(replyTo, IpcProtocol.MSG_HANDSHAKE_RESULT, IpcCodec.encodeHandshakeResult(result))
                if (result.accepted) {
                    localHistoryBroker.attach(replyTo, hello.capabilities)
                    analysisResultBroadcaster.setClient(replyTo, hello.capabilities)
                } else {
                    localHistoryBroker.disconnect()
                    analysisResultBroadcaster.clearClient()
                }
            }
        }
    }

    private fun handleSubmit(message: Message) {
        val replyTo = message.replyTo ?: return
        val result = if (replyTo.binder == authenticatedClientBinder) {
            session.submit(CapturedMessage.fromBundle(message.data))
        } else {
            SubmitResult(false, RejectReason.AUTHENTICATION_REQUIRED)
        }
        Log.i(TAG, "IPC submit accepted=${result.accepted} reason=${result.reason}")
        send(replyTo, IpcProtocol.MSG_SUBMIT_RESULT, IpcCodec.encodeSubmitResult(result))
    }

    private fun handleDisconnect(message: Message) {
        if (message.replyTo?.binder == authenticatedClientBinder) {
            cancelAllChatAssistantRequests()
            localHistoryBroker.disconnect()
            analysisResultBroadcaster.clearClient()
            realtimeCoordinator.invalidateVisibleViewport()
            session.disconnect()
            authenticatedClientBinder = null
        }
    }

    private fun handleChatAssistantRequest(message: Message) {
        val replyTo = message.replyTo ?: return
        val request = runCatching { ChatAssistantRequest.fromBundle(message.data) }.getOrElse {
            ChatAssistantRequest.validationFailure(message.data)?.let { failure ->
                if (replyTo.binder == authenticatedClientBinder) {
                    send(replyTo, IpcProtocol.MSG_CHAT_ASSISTANT_RESULT, failure.toBundle())
                }
            }
            return
        }
        cancelChatAssistantRequest(assistantRequestByConversation[request.conversationId].orEmpty())
        assistantRequests[request.requestId]?.let { previous ->
            cancelChatAssistantRequest(request.requestId)
            assistantRequestByConversation.remove(previous.conversationId, request.requestId)
        }
        try {
            assistantForeground.acquire(request.requestId)
        } catch (error: RuntimeException) {
            Log.w(TAG, "AI assistant foreground start failed cause=${error.javaClass.simpleName}")
            send(replyTo, IpcProtocol.MSG_CHAT_ASSISTANT_RESULT, ChatAssistantResult(
                requestId = request.requestId,
                conversationId = request.conversationId,
                turns = emptyList(),
                error = "系统暂不允许后台分析，请打开 Jev 后返回微信重试，并允许 Jev 后台运行。",
            ).toBundle())
            return
        }
        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        chatAssistantCoordinator.handle(request) { partial ->
                            serviceScope.launch {
                                if (replyTo.binder == authenticatedClientBinder &&
                                    assistantRequests[request.requestId]?.job === job && job.isActive) {
                                    send(replyTo, IpcProtocol.MSG_CHAT_ASSISTANT_PROGRESS, partial.toBundle())
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    ChatAssistantResult(
                        requestId = request.requestId,
                        conversationId = request.conversationId,
                        turns = emptyList(),
                        error = userFacingAssistantError(error),
                    )
                }
                if (replyTo.binder == authenticatedClientBinder) {
                    send(replyTo, IpcProtocol.MSG_CHAT_ASSISTANT_RESULT, result.toBundle())
                }
            } finally {
                if (assistantRequests[request.requestId]?.job === job) {
                    assistantRequests.remove(request.requestId)
                    assistantRequestByConversation.remove(request.conversationId, request.requestId)
                    assistantForeground.release(request.requestId)
                }
            }
        }
        assistantRequests[request.requestId] = ActiveAssistantRequest(request.conversationId, job)
        assistantRequestByConversation[request.conversationId] = request.requestId
        job.start()
    }

    private fun cancelChatAssistantRequest(requestId: String) {
        if (requestId.isBlank()) return
        val active = assistantRequests.remove(requestId) ?: return
        assistantRequestByConversation.remove(active.conversationId, requestId)
        active.job.cancel()
        assistantForeground.release(requestId)
    }

    private fun cancelAllChatAssistantRequests() {
        val active = assistantRequests.values.toList()
        assistantRequests.clear()
        assistantRequestByConversation.clear()
        active.forEach { it.job.cancel() }
        assistantForeground.clear()
    }

    private fun userFacingAssistantError(error: Throwable): String = when (error) {
        is ChatAssistantFailure -> {
            Log.w(TAG, "AI assistant failed stage=${error.stage} cause=${error.cause?.javaClass?.simpleName}")
            when (error.stage) {
                ChatAssistantFailure.Stage.LOCAL_HISTORY ->
                    "读取微信本地聊天记录失败，请确认微信连接正常且聊天记录可读取后重试。"
                ChatAssistantFailure.Stage.MODEL_CONFIGURATION ->
                    "读取理解模型配置失败，请检查 Jev 中的理解模型设置后重试。"
                ChatAssistantFailure.Stage.MODEL_REQUEST ->
                    "请求理解模型失败：${ChatAssistantErrorMessage.modelRequest(error.cause)}"
                ChatAssistantFailure.Stage.SESSION_STORAGE ->
                    "保存 AI分析会话失败，请重试。"
            }
        }
        else -> when {
        error.message?.contains("provider is not configured", ignoreCase = true) == true ->
            "请先在 Jev 设置中配置理解模型。"
        error.message?.contains("本地文本记录") == true -> error.message.orEmpty().take(200)
        error.message?.contains("微信连接") == true || error.message?.contains("聊天对象") == true ->
            error.message.orEmpty().take(200)
        else -> "读取完整聊天记录或请求理解模型失败，请检查微信本地记录连接和理解模型配置后重试。"
        }
    }

    private fun send(target: Messenger, what: Int, data: android.os.Bundle) {
        runCatching {
            target.send(Message.obtain(null, what).apply { this.data = data })
        }.onFailure { error ->
            if (error is RemoteException) {
                cancelAllChatAssistantRequests()
                localHistoryBroker.disconnect()
                analysisResultBroadcaster.clearClient()
                session.disconnect()
                authenticatedClientBinder = null
            }
        }
    }

    private fun isAuthenticated(message: Message): Boolean =
        AuthenticatedIpcClientPolicy.allows(
            authenticatedBinder = authenticatedClientBinder,
            replyBinder = message.replyTo?.binder,
            callerPackages = packageManager.getPackagesForUid(message.sendingUid).orEmpty().toSet(),
        )

    private companion object {
        const val TAG = "JevIpcService"
    }
}
