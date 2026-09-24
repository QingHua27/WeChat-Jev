package com.jev.relationship.xposed.ui

import android.content.Context
import java.security.MessageDigest

object JevConversationAnalysisPrefs {
    fun isReplyHidden(context: Context, conversationId: String): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("reply_hidden_${key(conversationId)}", false)

    fun setReplyHidden(context: Context, conversationId: String, hidden: Boolean) {
        if (conversationId.isBlank()) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("reply_hidden_${key(conversationId)}", hidden).apply()
    }

    fun isEnabled(context: Context, conversationId: String): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(key(conversationId), true)

    fun setEnabled(context: Context, conversationId: String, enabled: Boolean) {
        if (conversationId.isBlank()) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(conversationId), enabled)
            .apply()
    }

    private fun key(conversationId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "conversation_enabled_$hash"
    }

    private const val PREFERENCES = "jev_conversation_analysis"
}
