package com.jev.relationship.xposed.hook

import com.jev.relationship.ipc.CapturedMessage
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Method

interface WechatMessageHook {
    fun install(context: android.content.Context, emit: (CapturedMessage) -> Unit): HookInstallResult

    fun uninstall()
}

fun interface WechatHookHandle {
    fun unhook()
}

fun interface WechatAfterHookInstaller {
    fun install(target: Method, after: (Any?) -> Unit): WechatHookHandle
}

class LibXposedAfterHookInstaller(
    private val xposed: XposedInterface,
) : WechatAfterHookInstaller {
    override fun install(target: Method, after: (Any?) -> Unit): WechatHookHandle {
        val handle = xposed.hook(target).intercept { chain: Chain ->
            val result = chain.proceed()
            runCatching { after(chain.getArg(0)) }
            result
        }
        return WechatHookHandle { handle.unhook() }
    }
}

fun interface WechatUiHookInstaller {
    fun install(target: Method, after: (Any?, Any?) -> Unit): WechatHookHandle
    fun installBefore(target: Method, before: (Any?) -> Unit): WechatHookHandle = WechatHookHandle {}
}

class LibXposedUiHookInstaller(
    private val xposed: XposedInterface,
) : WechatUiHookInstaller {
    override fun installBefore(target: Method, before: (Any?) -> Unit): WechatHookHandle {
        val handle = xposed.hook(target).intercept { chain: Chain ->
            runCatching { before(chain.thisObject) }
            chain.proceed()
        }
        return WechatHookHandle { handle.unhook() }
    }

    override fun install(target: Method, after: (Any?, Any?) -> Unit): WechatHookHandle {
        val handle = xposed.hook(target).intercept { chain: Chain ->
            val result = chain.proceed()
            runCatching { after(chain.thisObject, result) }
            result
        }
        return WechatHookHandle { handle.unhook() }
    }
}
