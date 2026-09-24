package com.jev.relationship.xposed.ui

import android.view.View
import android.view.ViewGroup

data class WechatChatViewHost(
    val chatList: View,
    val container: ViewGroup,
)

class WechatChatViewLocator(
    private val chatListClassName: String = CHAT_LIST_CLASS_NAME,
) {
    fun locate(root: View): WechatChatViewHost? {
        val matches = mutableListOf<View>()
        collect(root, matches)
        val chatList = matches
            .filter { it.isShown && it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: matches.singleOrNull()
            ?: return null
        val container = chatList.parent as? ViewGroup ?: root as? ViewGroup ?: return null
        return WechatChatViewHost(chatList = chatList, container = container)
    }

    private fun collect(view: View, matches: MutableList<View>) {
        val resourceName = if (view.id == View.NO_ID) null else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (view.javaClass.name == chatListClassName || resourceName == CHAT_LIST_RESOURCE_ID) matches += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collect(view.getChildAt(index), matches)
            }
        }
    }

    private companion object {
        const val CHAT_LIST_CLASS_NAME = "com.tencent.mm.ui.chatting.view.MMChattingListView"
        const val CHAT_LIST_RESOURCE_ID = "c9o"
    }
}
