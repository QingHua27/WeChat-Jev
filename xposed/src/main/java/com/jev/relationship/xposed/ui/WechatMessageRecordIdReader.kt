package com.jev.relationship.xposed.ui

import android.view.View

/** Reads the local f9 record already bound to a WeChat 8.0.72 text bubble. */
object WechatMessageRecordIdReader {
    private const val MESSAGE_CLASS = "com.tencent.mm.storage.f9"

    fun read(view: View): Long? = runCatching {
        val bubbleTag = view.tag ?: return null
        val textItem = field(bubbleTag, "a") ?: return null
        val messageHolder = field(textItem, "d") ?: return null
        val message = field(messageHolder, "b") ?: return null
        if (message.javaClass.name != MESSAGE_CLASS) return null
        (message.javaClass.getMethod("getMsgId").invoke(message) as Number).toLong()
            .takeIf { it > 0L }
    }.getOrNull()

    private fun field(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { member ->
                member.isAccessible = true
                return member.get(instance)
            }
            type = type.superclass
        }
        return null
    }
}
