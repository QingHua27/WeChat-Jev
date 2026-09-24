package com.jev.relationship.ipc

import android.os.Bundle

/** Blank text clears a stale suggestion while the latest incoming message is analyzed. */
data class IpcReplySuggestion(val conversationHash: String, val messageId: String, val text: String) {
    fun toBundle() = Bundle().apply {
        putString(IpcProtocol.KEY_CONVERSATION_ID, conversationHash.take(IpcProtocol.MAX_HASH_LENGTH))
        putString(IpcProtocol.KEY_MESSAGE_ID, messageId.take(IpcProtocol.MAX_MESSAGE_ID_LENGTH))
        putString(IpcProtocol.KEY_DETAIL_REPLY, text.take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH))
    }

    companion object {
        fun fromBundle(value: Bundle) = IpcReplySuggestion(
            value.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty().take(IpcProtocol.MAX_HASH_LENGTH),
            value.getString(IpcProtocol.KEY_MESSAGE_ID).orEmpty().take(IpcProtocol.MAX_MESSAGE_ID_LENGTH),
            value.getString(IpcProtocol.KEY_DETAIL_REPLY).orEmpty().take(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH),
        )
    }
}
