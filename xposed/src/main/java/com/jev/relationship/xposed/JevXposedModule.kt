package com.jev.relationship.xposed

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.IpcProtocol
import com.jev.relationship.ipc.MessageSender
import com.jev.relationship.xposed.hook.Wechat072MessageAdapter
import com.jev.relationship.xposed.hook.WechatHookGate
import com.jev.relationship.xposed.hook.WechatMessageHook
import com.jev.relationship.xposed.hook.WechatVersion
import com.jev.relationship.xposed.ipc.JevIpcClient
import com.jev.relationship.xposed.ui.WechatChatUiHook

class JevXposedModule : XposedModule() {
    private var client: JevIpcClient? = null
    private var messageHook: WechatMessageHook? = null
    private var chatUiHook: WechatChatUiHook? = null
    private var attachHookInstalled = false
    private var processName: String? = null
    private var historyReader: com.jev.relationship.xposed.hook.WechatLocalHistoryReader? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        Log.i(TAG, "module loaded in process=${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!WechatScope.isSupported(param.packageName) || attachHookInstalled) return

        val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attachMethod).intercept { chain ->
            val result = chain.proceed()
            (chain.getArg(0) as? Context)?.let(::initializeClient)
            result
        }
        attachHookInstalled = true
        Log.i(TAG, "Application.attach hook installed for ${param.packageName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "package ready: ${param.packageName}")
        if (!WechatScope.isSupported(param.packageName)) {
            Log.i(TAG, "ignored unsupported package: ${param.packageName}")
            return
        }
        currentApplicationContext()?.let(::initializeClient)
    }

    private fun initializeClient(appContext: Context) {
        if (client != null) return
        val preferences = getRemotePreferences(XposedModulePreferences.NAME)
        client = JevIpcClient(
            context = appContext,
            pairingTokenProvider = {
                getRemotePreferences(XposedModulePreferences.NAME)
                    .getString(XposedModulePreferences.PAIRING_TOKEN, null)
            },
            moduleVersion = "0.1.1",
            onAnalysisResult = { result -> chatUiHook?.onAnalysisResult(result) },
            onReplySuggestion = { result -> chatUiHook?.onReplySuggestion(result) },
            onDisconnected = { chatUiHook?.clear() },
            onConnected = { chatUiHook?.onConnectionRestored() },
            onRegenerationStatus = { conversationId, status -> chatUiHook?.onRegenerationStatus(conversationId, status) },
            onHistoryRequest = { request, reply ->
                val reader = historyReader
                if (reader == null) reply(com.jev.relationship.ipc.LocalHistoryPage(request.requestId, request.conversationId, error = "当前微信版本不支持本地记录读取"))
                else reader.read(request, reply)
            },
        )
        val pairingToken = preferences
            .getString(XposedModulePreferences.PAIRING_TOKEN, null)
            ?.trim()
        Log.i(TAG, "IPC client created; pairing token configured=${!pairingToken.isNullOrEmpty()}")
        installWechatMessageHook(appContext, pairingToken)
        installWechatChatUiHook(appContext, pairingToken)
        val runtimePreferences = appContext.getSharedPreferences("jev_xposed_runtime", Context.MODE_PRIVATE)
        val currentProcessName = processName.orEmpty()
        if (currentProcessName == WechatHookGate.MAIN_PROCESS) {
            client?.start()
        }
        Log.i(TAG, "init process=$currentProcessName simulation=${preferences.getBoolean(XposedModulePreferences.SIMULATION_ENABLED, false)} consumed=${runtimePreferences.getBoolean("phase3_simulation_consumed_push_v3", false)}")
        if (
            preferences.getBoolean(XposedModulePreferences.SIMULATION_ENABLED, false) &&
            currentProcessName.endsWith(":push") &&
            !runtimePreferences.getBoolean("phase3_simulation_consumed_push_v3", false)
        ) {
            runtimePreferences.edit().putBoolean("phase3_simulation_consumed_push_v3", true).apply()
            val now = System.currentTimeMillis()
            client?.submit(
                CapturedMessage(
                    conversationId = "jev-phase3-simulation",
                    sender = MessageSender.CONTACT,
                    text = "Jev Phase 3 simulated WeChat message",
                    timestampMs = now,
                    sourcePackage = IpcProtocol.WECHAT_PACKAGE,
                    sourceClass = "Phase3SimulatedMessageSource",
                    isOutgoing = false,
                    messageId = "jev-phase3-simulation-$now",
                ),
            )
            Log.i(TAG, "one-shot Phase 3 simulation queued")
        }
    }

    private fun installWechatMessageHook(appContext: Context, pairingToken: String?) {
        if (messageHook != null) return

        val version = readWechatVersion(appContext) ?: return
        val currentProcessName = processName
        if (!WechatHookActivation.shouldInstall(
                packageName = appContext.packageName,
                processName = currentProcessName,
                version = version,
                pairingToken = pairingToken,
            )
        ) {
            Log.i(
                TAG,
                "wechat hook not installed: process=${currentProcessName.orEmpty()} version=${version.versionName}/${version.versionCode} tokenConfigured=${!pairingToken.isNullOrEmpty()}",
            )
            return
        }

        val adapter = Wechat072MessageAdapter(
            module = this,
            logger = { category -> Log.w(TAG, "wechat hook $category") },
            onHistoryInvalidated = { client?.invalidateHistory() },
        )
        val result = adapter.install(appContext) { capturedMessage ->
            if (com.jev.relationship.xposed.ui.JevConversationAnalysisPrefs
                    .isEnabled(appContext, capturedMessage.conversationId)
            ) client?.submit(capturedMessage)
        }
        if (result == com.jev.relationship.xposed.hook.HookInstallResult.INSTALLED) {
            messageHook = adapter
            Log.i(TAG, "wechat hook installed for ${version.versionName}/${version.versionCode}")
        } else {
            Log.w(TAG, "wechat hook not installed: result=${result.name}")
        }
    }

    private fun installWechatChatUiHook(appContext: Context, pairingToken: String?) {
        if (chatUiHook != null) return
        val version = readWechatVersion(appContext) ?: return
        if (!WechatUiHookActivation.shouldInstall(
                packageName = appContext.packageName,
                processName = processName,
                version = version,
                pairingToken = pairingToken,
            )
        ) {
            Log.i(TAG, "wechat embedded UI hook not installed: process=${processName.orEmpty()} version=${version.versionName}/${version.versionCode} tokenConfigured=${!pairingToken.isNullOrEmpty()}")
            return
        }

        runCatching {
            com.jev.relationship.xposed.hook.WechatLocalHistoryReader(appContext, this).also { it.install() }
        }.onSuccess { historyReader = it }
            .onFailure { Log.w(TAG, "history reader unavailable type=${it.javaClass.simpleName}") }
        val hook = WechatChatUiHook(
            context = appContext,
            installer = com.jev.relationship.xposed.hook.LibXposedUiHookInstaller(this),
            onConversationToggle = { conversationId, title, enabled ->
                client?.setConversationAnalysis(conversationId, title, enabled)
            },
            onVisibleChat = { snapshot -> client?.submitVisibleChat(snapshot) },
            onChatResumed = { client?.ensureConnected() },
            onRegenerateConversation = { conversationId -> client?.regenerateRecentConversation(conversationId) ?: false },
            onChatAssistantRequest = { request, callback ->
                client?.requestChatAssistant(request, callback) ?: false
            },
            onCancelChatAssistantRequest = { requestId -> client?.cancelChatAssistantRequest(requestId) },
        )
        val result = hook.install()
        if (result == com.jev.relationship.xposed.hook.HookInstallResult.INSTALLED) {
            chatUiHook = hook
            Log.i(TAG, "wechat embedded UI hook installed for ${version.versionName}/${version.versionCode}")
        } else {
            Log.w(TAG, "wechat embedded UI hook not installed: result=${result.name}")
        }
    }

    private fun readWechatVersion(appContext: Context): WechatVersion? {
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(WechatHookGate.PACKAGE_NAME, 0)
        }.getOrElse {
            Log.w(TAG, "wechat hook version lookup failed")
            return null
        }
        return WechatVersion(
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            },
        )
    }

    private fun currentApplicationContext(): Context? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThreadClass
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
        if (currentApplication != null) return@runCatching currentApplication

        val activityThread = activityThreadClass
            .getDeclaredMethod("currentActivityThread")
            .invoke(null)
        val initialApplication = activityThreadClass
            .getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }
            .get(activityThread) as? Application
        initialApplication
            ?: activityThreadClass
                .getDeclaredField("mAllApplications")
                .apply { isAccessible = true }
                .get(activityThread)
                .let { it as? List<*> }
                ?.filterIsInstance<Application>()
                ?.firstOrNull()
    }.getOrNull()

    private companion object {
        const val TAG = "JevXposed"
    }
}
