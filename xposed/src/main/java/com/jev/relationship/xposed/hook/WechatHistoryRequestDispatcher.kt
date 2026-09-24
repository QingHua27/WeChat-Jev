package com.jev.relationship.xposed.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jev.relationship.ipc.LocalHistoryPage
import com.jev.relationship.ipc.LocalHistoryRequest
import java.util.concurrent.Executor

/** Keeps fragment access on main and database work off main. */
internal class WechatHistoryRequestDispatcher(
    private val activeTalker: () -> String?,
    private val readPage: (LocalHistoryRequest, String) -> LocalHistoryPage,
    private val worker: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    fun read(request: LocalHistoryRequest, reply: (LocalHistoryPage) -> Unit) {
        attempt(request, reply, SystemClock.uptimeMillis() + READY_WAIT_MS)
    }

    private fun attempt(request: LocalHistoryRequest, reply: (LocalHistoryPage) -> Unit, deadline: Long) {
        val talker = currentTalker()
        if (talker == null || (request.conversationId.isNotBlank() && talker != request.conversationId)) {
            // The action bar can be clicked before the new fragment's onResume.
            // Wait for that exact conversation; never substitute the previous chat.
            if (SystemClock.uptimeMillis() < deadline) {
                main.postDelayed({ attempt(request, reply, deadline) }, 100L)
                return
            }
            reply(LocalHistoryPage(request.requestId, request.conversationId, error = "当前聊天尚未就绪"))
            return
        }
        worker.execute {
            val page = runCatching { readPage(request, talker) }.getOrElse {
                android.util.Log.w("JevLocalHistory", "read failed type=${it.javaClass.simpleName} cause=${it.cause?.javaClass?.simpleName}")
                LocalHistoryPage(request.requestId, talker, error = "微信本地记录读取失败，未使用可见片段替代")
            }
            main.post {
                reply(if (currentTalker() == talker) page else
                    LocalHistoryPage(request.requestId, talker, error = "聊天对象已改变，请在当前聊天重新分析"))
            }
        }
    }

    private fun currentTalker(): String? = runCatching { activeTalker() }
        .getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val READY_WAIT_MS = 2_000L
    }
}
