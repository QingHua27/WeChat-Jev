package com.jev.relationship.xposed.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.ChatAssistantRequest
import com.jev.relationship.ipc.ChatAssistantResult
import com.jev.relationship.ipc.IpcAnalysisResult
import com.jev.relationship.ipc.IpcProtocol

class JevIpcClient(
    private val context: Context,
    private val pairingTokenProvider: () -> String?,
    private val moduleVersion: String,
    private val onAnalysisResult: (IpcAnalysisResult) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onHistoryRequest: (com.jev.relationship.ipc.LocalHistoryRequest, (com.jev.relationship.ipc.LocalHistoryPage) -> Unit) -> Unit = { _, _ -> },
    private val onRegenerationStatus: (String, String) -> Unit = { _, _ -> },
    private val onReplySuggestion: (com.jev.relationship.ipc.IpcReplySuggestion) -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onAnalysisResults: (List<IpcAnalysisResult>) -> Unit = { it.forEach(onAnalysisResult) },
    private val onFastCacheDisplay: (Boolean) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val state = IpcClientState(pairingTokenProvider)
    private var service: Messenger? = null
    private var bound = false
    private var retryAttempt = 0
    private var authenticated = false
    private var closed = false
    private val reconnectRunnable = Runnable { connect() }
    private var helloAttempt = 0
    private val helloRetry = Runnable { if (service != null && !authenticated) sendHello() }
    private val assistantTimeouts = mutableMapOf<String, Runnable>()
    private val startupGate = IpcClientStartupGate {
        handler.post {
            if (!bound && service == null) connect()
        }
    }

    private val responseRouter = IpcResponseRouter(
        onHandshake = { result ->
            authenticated = result.accepted
            state.onHandshakeResult(result)
            if (authenticated) {
                helloAttempt = 0
                handler.removeCallbacks(helloRetry)
                onConnected()
            } else scheduleHello()
            drainQueue()
        },
        onAnalysisResult = onAnalysisResult,
        onAnalysisResults = onAnalysisResults,
        onFastCacheDisplay = onFastCacheDisplay,
        onAnalysisCleared = onDisconnected,
    )

    private val responseMessenger: Messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == IpcProtocol.MSG_REPLY_SUGGESTION) {
                if (authenticated) runCatching {
                    onReplySuggestion(com.jev.relationship.ipc.IpcReplySuggestion.fromBundle(message.data))
                }
                return
            }
            if (message.what == IpcProtocol.MSG_REGENERATE_STATUS) {
                if (authenticated) onRegenerationStatus(
                    message.data.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
                    message.data.getString(IpcProtocol.KEY_REASON).orEmpty().take(256),
                )
                return
            }
            if (message.what == IpcProtocol.MSG_HISTORY_REQUEST) {
                if (authenticated) runCatching {
                    val request = com.jev.relationship.ipc.LocalHistoryRequest.fromBundle(message.data)
                    onHistoryRequest(request) { page ->
                        if (authenticated) service?.let { target ->
                            send(target, Message.obtain(null, IpcProtocol.MSG_HISTORY_PAGE).apply {
                                data = page.toBundle()
                                replyTo = responseMessenger
                            })
                        }
                    }
                }
                return
            }
            if (!responseRouter.handle(message) && message.what != IpcProtocol.MSG_SUBMIT_RESULT) {
                super.handleMessage(message)
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let(::Messenger)
            retryAttempt = 0
            Log.i(TAG, "IPC service connected")
            sendHello()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionLost()
        }

        override fun onBindingDied(name: ComponentName?) = connectionLost()
        override fun onNullBinding(name: ComponentName?) = connectionLost()
    }

    @Synchronized
    fun submit(message: CapturedMessage): Boolean {
        if (!state.enqueue(message)) return false
        handler.post {
            if (!bound && service == null) connect()
            drainQueue()
        }
        return true
    }

    fun start() {
        startupGate.start()
    }

    /** A foreground chat probes an existing binder or reconnects without restarting WeChat. */
    fun ensureConnected() {
        handler.post {
            if (closed) return@post
            handler.removeCallbacks(reconnectRunnable)
            if (service != null) sendHello() else if (!bound) connect()
        }
    }

    fun close() {
        closed = true
        authenticated = false
        handler.removeCallbacksAndMessages(null)
        state.onDisconnected()
        responseRouter.failAssistantRequests("Jev 连接已关闭")
        onDisconnected()
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
    }

    fun requestChatAssistant(
        request: ChatAssistantRequest,
        callback: (ChatAssistantResult) -> Unit,
    ): Boolean {
        if (!authenticated) return false
        runCatching {
            responseRouter.registerAssistantRequest(request.requestId, request.conversationId) { result ->
                if (!result.isPartial) assistantTimeouts.remove(request.requestId)?.let(handler::removeCallbacks)
                callback(result)
            }
        }.getOrElse { return false }
        val timeout = Runnable {
            assistantTimeouts.remove(request.requestId)
            responseRouter.failAssistantRequest(request.requestId, "分析请求超时，请重试。")
        }
        assistantTimeouts[request.requestId] = timeout
        handler.postDelayed(timeout, CHAT_ASSISTANT_TIMEOUT_MS)
        handler.post {
            if (!authenticated) {
                responseRouter.failAssistantRequests("Jev 连接已断开，请重试")
                return@post
            }
            val target = service
            if (target == null) {
                responseRouter.failAssistantRequests("Jev 连接已断开，请重试")
            } else {
                send(target, Message.obtain(null, IpcProtocol.MSG_CHAT_ASSISTANT_REQUEST).apply {
                    data = request.toBundle()
                    replyTo = responseMessenger
                })
            }
        }
        return true
    }

    fun cancelChatAssistantRequest(requestId: String) {
        val wasPending = responseRouter.cancelAssistantRequest(requestId)
        assistantTimeouts.remove(requestId)?.let(handler::removeCallbacks)
        if (wasPending) handler.post {
            if (!authenticated) return@post
            service?.let { target ->
                send(target, Message.obtain(null, IpcProtocol.MSG_CHAT_ASSISTANT_CANCEL).apply {
                    data = android.os.Bundle().apply { putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, requestId) }
                    replyTo = responseMessenger
                })
            }
        }
    }

    private fun connect() {
        if (closed || bound || service != null || !state.canConnect()) return
        val intent = Intent(IpcProtocol.SERVICE_ACTION).apply {
            component = ComponentName(
                "com.jev.relationship",
                "com.jev.relationship.ipc.JevIpcService",
            )
        }
        bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) scheduleReconnect()
        else Log.i(TAG, "IPC bind requested")
    }

    private fun sendHello() {
        val target = service ?: return
        val hello = runCatching { state.hello(moduleVersion) }.getOrNull()
        if (hello == null) {
            scheduleHello()
            return
        }
        send(target, Message.obtain(null, IpcProtocol.MSG_HELLO).apply {
            data = hello.toBundle()
            replyTo = responseMessenger
        })
    }

    private fun scheduleHello() {
        handler.removeCallbacks(helloRetry)
        if (service != null && !authenticated) {
            handler.postDelayed(helloRetry, BackoffPolicy.delayMillis(++helloAttempt))
        }
    }

    private fun drainQueue() {
        val target = service ?: return
        while (true) {
            val next = state.nextAuthenticatedMessage() ?: return
            val sent = send(target, Message.obtain(null, IpcProtocol.MSG_SUBMIT).apply {
                data = next.toBundle()
                replyTo = responseMessenger
            })
            if (!sent) return
        }
    }

    private fun send(target: Messenger, message: Message): Boolean = runCatching {
        target.send(message)
        true
    }.getOrElse {
        connectionLost()
        false
    }

    private fun releaseBinding() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    private fun connectionLost() {
        authenticated = false
        releaseBinding()
        state.onDisconnected()
        responseRouter.failAssistantRequests("Jev 连接已断开，请重试")
        onDisconnected()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (closed || !state.canConnect()) return
        val delay = BackoffPolicy.delayMillis(++retryAttempt)
        handler.removeCallbacks(helloRetry)
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    fun invalidateHistory() {
        handler.post {
            onDisconnected()
            if (authenticated) service?.let { target ->
                send(target, Message.obtain(null, IpcProtocol.MSG_HISTORY_INVALIDATED).apply { replyTo = responseMessenger })
            }
        }
    }

    fun setConversationAnalysis(conversationId: String, title: String, enabled: Boolean) {
        if (conversationId.isBlank()) return
        handler.post {
            if (!authenticated) return@post
            val data = android.os.Bundle().apply {
                putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId)
                putString(IpcProtocol.KEY_CONVERSATION_TITLE, title)
                putBoolean(IpcProtocol.KEY_ANALYSIS_ENABLED, enabled)
            }
            service?.let { target ->
                send(target, Message.obtain(null, IpcProtocol.MSG_CONVERSATION_TOGGLE).apply {
                    this.data = data
                    replyTo = responseMessenger
                })
            }
        }
    }

    fun submitVisibleChat(value: com.jev.relationship.ipc.IpcVisibleChatConversation) {
        if (value.conversationId.isBlank() || value.messages.isEmpty()) return
        handler.post {
            if (!authenticated) return@post
            service?.let { target ->
                send(target, Message.obtain(null, IpcProtocol.MSG_VISIBLE_CHAT).apply {
                    data = value.toBundle()
                    replyTo = responseMessenger
                })
            }
        }
    }

    fun regenerateRecentConversation(conversationId: String): Boolean {
        if (conversationId.isBlank() || !authenticated || service == null) return false
        // Queue after a possible enable-toggle, preserving message order.
        handler.post {
            val target = service
            if (!authenticated || target == null || !send(target,
                    Message.obtain(null, IpcProtocol.MSG_REGENERATE_RECENT).apply {
                        data = android.os.Bundle().apply { putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId) }
                        replyTo = responseMessenger
                    })) {
                onRegenerationStatus(conversationId, "连接已断开，原有缓存已保留")
            }
        }
        return true
    }

    private companion object {
        const val TAG = "JevIpcClient"
        const val CHAT_ASSISTANT_TIMEOUT_MS = IpcProtocol.CHAT_ASSISTANT_TIMEOUT_MS
    }
}
