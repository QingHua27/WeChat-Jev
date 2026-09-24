package com.jev.relationship.xposed.hook

import android.content.Context
import android.database.Cursor
import com.jev.relationship.ipc.LocalHistoryPage
import com.jev.relationship.ipc.LocalHistoryRequest
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class WechatLocalHistoryReader(private val context: Context, private val module: XposedInterface) {
    private var fragment = WeakReference<Any>(null)
    private val worker = Executors.newSingleThreadExecutor()
    private val dispatcher = WechatHistoryRequestDispatcher(
        activeTalker = {
            fragment.get()?.let { current ->
                current.javaClass.getMethod("getStringExtra", String::class.java)
                    .invoke(current, "Chat_User") as? String
            }
        },
        readPage = { request, talker -> openStore(talker).read(request, talker) },
        worker = worker,
    )

    fun install() {
        val type = Class.forName("com.tencent.mm.ui.chatting.ChattingUIFragment", false, context.classLoader)
        for (name in listOf("onResume", "onPause")) {
            val method = type.getMethod(name)
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val value = chain.thisObject
                if (value != null && type.isInstance(value)) {
                    if (name == "onResume") fragment = WeakReference(value)
                    else if (fragment.get() === value) fragment.clear()
                }
                result
            }
        }
    }

    fun read(request: LocalHistoryRequest, reply: (LocalHistoryPage) -> Unit) {
        dispatcher.read(request, reply)
    }

    private fun openStore(talker: String): WechatHistoryStore {
        fun type(name: String) = Class.forName(name, false, context.classLoader)
        // Verified 8.0.72: kernel service -> message store -> existing unlocked WCDB handle.
        val service = type("em0.k1").getMethod("s", Class::class.java).invoke(null, type("fg3.x3"))
        val storage = service.javaClass.getMethod("ei").invoke(service)
        val table = storage.javaClass.getMethod("Ta", String::class.java).invoke(storage, talker) as String
        val deletion = storage.javaClass.getMethod("O1", String::class.java).invoke(storage, talker) as String
        val deletedBefore = if (deletion.isBlank()) 0L else {
            checkNotNull(Regex("\\s*createTime > (\\d+) AND\\s*").matchEntire(deletion)) { "Unknown deletion predicate" }.groupValues[1].toLong()
        }
        val wrapper = storage.javaClass.getField("r").get(storage)
        val database = wrapper.javaClass.getMethod("s").invoke(wrapper)
        val rawQuery = database.javaClass.methods.firstOrNull { method ->
            method.name == "rawQueryWithFactory" && method.parameterTypes.size == 4
        } ?: error("WCDB raw query API unavailable")
        rawQuery.isAccessible = true
        return WechatHistoryStore(table, deletedBefore) { sql, args ->
            @Suppress("UNCHECKED_CAST")
            rawQuery.invoke(database, null, sql, args as Array<Any>, table) as Cursor
        }
    }

}
