package com.jev.relationship.xposed.hook

import android.content.Context
import com.jev.relationship.ipc.CapturedMessage
import com.jev.relationship.ipc.CapturedMessageValidator
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class Wechat072MessageAdapter(
    private val installer: WechatAfterHookInstaller,
    private val deduplicator: WechatMessageDeduplicator = WechatMessageDeduplicator(),
    private val classResolver: (String, ClassLoader) -> Class<*> = { name, loader ->
        Class.forName(name, false, loader)
    },
    private val logger: (String) -> Unit = {},
    private val onHistoryInvalidated: () -> Unit = {},
) : WechatMessageHook {
    constructor(
        module: XposedInterface,
        deduplicator: WechatMessageDeduplicator = WechatMessageDeduplicator(),
        logger: (String) -> Unit = {},
        onHistoryInvalidated: () -> Unit = {},
    ) : this(
        installer = LibXposedAfterHookInstaller(module),
        deduplicator = deduplicator,
        logger = logger,
        onHistoryInvalidated = onHistoryInvalidated,
    )

    private var installed = false
    private val hookHandles = mutableListOf<WechatHookHandle>()
    private var accessors: Accessors? = null
    private var emitter: ((CapturedMessage) -> Unit)? = null

    override fun install(context: Context, emit: (CapturedMessage) -> Unit): HookInstallResult =
        installForClassLoader(context.classLoader, emit)

    @Synchronized
    internal fun installForClassLoader(
        classLoader: ClassLoader,
        emit: (CapturedMessage) -> Unit = {},
    ): HookInstallResult {
        if (installed) return HookInstallResult.ALREADY_INSTALLED

        val messageClass = runCatching {
            classResolver("com.tencent.mm.storage.f9", classLoader)
        }.getOrElse {
            logger("target_class_unavailable")
            return HookInstallResult.TARGET_CLASS_UNAVAILABLE
        }
        val storageClass = runCatching {
            classResolver("com.tencent.mm.storage.h9", classLoader)
        }.getOrElse {
            logger("target_class_unavailable")
            return HookInstallResult.TARGET_CLASS_UNAVAILABLE
        }
        val baseMessageClass = runCatching {
            classResolver("sm.b8", classLoader)
        }.getOrElse {
            logger("target_class_unavailable")
            return HookInstallResult.TARGET_CLASS_UNAVAILABLE
        }
        if (!baseMessageClass.isAssignableFrom(messageClass)) {
            logger("message_class_hierarchy_unavailable")
            return HookInstallResult.FAILED
        }
        val targetMethods = runCatching {
            storageClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.firstOrNull() == messageClass
                }
                .sortedWith(
                    compareByDescending<Method> { it.name == "Cb" }
                        .thenBy { it.name },
                )
        }.getOrElse {
            logger("target_method_unavailable")
            return HookInstallResult.FAILED
        }
        if (targetMethods.isEmpty()) {
            logger("target_method_unavailable")
            return HookInstallResult.TARGET_METHOD_UNAVAILABLE
        }
        val resolvedAccessors = runCatching {
            Accessors(
                type = messageClass.getMethod("getType"),
                content = messageClass.getMethod("j"),
                talker = messageClass.getMethod("O0"),
                isSend = messageClass.getMethod("C0"),
                createTime = messageClass.getMethod("getCreateTime"),
                localMessageId = messageClass.getMethod("getMsgId"),
                serverMessageId = messageClass.getMethod("I0"),
            )
        }.getOrElse {
            logger("message_accessor_unavailable")
            return HookInstallResult.FAILED
        }

        val handles = runCatching {
            targetMethods.map { targetMethod ->
                installer.install(targetMethod) { rawMessage ->
                    handleCapturedMessage(rawMessage, resolvedAccessors)
                }
            }
        }.getOrElse {
            logger("hook_install_failed")
            return HookInstallResult.FAILED
        }

        accessors = resolvedAccessors
        emitter = emit
        hookHandles += handles
        installed = true
        logger("hook_installed methods=${targetMethods.size}")
        return HookInstallResult.INSTALLED
    }

    @Synchronized
    override fun uninstall() {
        runCatching { hookHandles.forEach(WechatHookHandle::unhook) }
            .onFailure { logger("hook_uninstall_failed") }
        hookHandles.clear()
        accessors = null
        emitter = null
        installed = false
    }

    private fun handleCapturedMessage(rawMessage: Any?, resolvedAccessors: Accessors) {
        val currentEmitter = emitter ?: return
        val snapshot = runCatching {
            snapshot(rawMessage, resolvedAccessors)
        }.getOrElse {
            logger("message_read_failed")
            return
        }
        if (snapshot.type == 10000 || snapshot.type == 10002) {
            if (snapshot.content.orEmpty().contains("撤回") || snapshot.content.orEmpty().contains("revokemsg")) {
                runCatching(onHistoryInvalidated)
            }
        }
        val message = runCatching { WechatMessageMapper.map(snapshot) }
            .getOrElse {
                logger("message_mapping_failed")
                return
            }
            ?: return
        val validation = runCatching { CapturedMessageValidator.validate(message) }
            .getOrElse {
                logger("message_validation_failed")
                return
            }
        if (!validation.accepted) {
            logger("message_rejected_${validation.reason?.name ?: "unknown"}")
            return
        }
        val messageId = message.messageId ?: return
        if (!deduplicator.shouldEmit(messageId)) return
        logger("message_captured type=1 outgoing=${message.isOutgoing} id=$messageId")
        runCatching { currentEmitter(message) }
            .onFailure { logger("message_emit_failed") }
    }

    private fun snapshot(rawMessage: Any?, resolvedAccessors: Accessors): WechatMessageSnapshot {
        requireNotNull(rawMessage)
        return WechatMessageSnapshot(
            type = (resolvedAccessors.type.invoke(rawMessage) as Number).toInt(),
            content = resolvedAccessors.content.invoke(rawMessage) as? String,
            talker = resolvedAccessors.talker.invoke(rawMessage) as? String,
            isOutgoing = (resolvedAccessors.isSend.invoke(rawMessage) as Number).toInt() != 0,
            createTime = (resolvedAccessors.createTime.invoke(rawMessage) as Number).toLong(),
            localMessageId = (resolvedAccessors.localMessageId.invoke(rawMessage) as Number).toLong(),
            serverMessageId = (resolvedAccessors.serverMessageId.invoke(rawMessage) as Number).toLong(),
        )
    }

    private data class Accessors(
        val type: Method,
        val content: Method,
        val talker: Method,
        val isSend: Method,
        val createTime: Method,
        val localMessageId: Method,
        val serverMessageId: Method,
    )
}
