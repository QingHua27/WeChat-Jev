package com.jev.relationship.ipc

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class IpcHello(
    val protocolVersion: Int,
    val pairingToken: String,
    val sourcePackage: String,
    val moduleVersion: String,
    val capabilities: Set<String> = emptySet(),
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putInt(IpcProtocol.KEY_PROTOCOL_VERSION, protocolVersion)
        putString(IpcProtocol.KEY_PAIRING_TOKEN, pairingToken)
        putString(IpcProtocol.KEY_SOURCE_PACKAGE, sourcePackage)
        putString(IpcProtocol.KEY_MODULE_VERSION, moduleVersion)
        putStringArrayList(IpcProtocol.KEY_CAPABILITIES, ArrayList(capabilities))
    }

    companion object {
        fun fromBundle(bundle: Bundle): IpcHello = IpcHello(
            protocolVersion = bundle.getInt(IpcProtocol.KEY_PROTOCOL_VERSION),
            pairingToken = bundle.getString(IpcProtocol.KEY_PAIRING_TOKEN).orEmpty(),
            sourcePackage = bundle.getString(IpcProtocol.KEY_SOURCE_PACKAGE).orEmpty(),
            moduleVersion = bundle.getString(IpcProtocol.KEY_MODULE_VERSION).orEmpty(),
            capabilities = bundle.getStringArrayList(IpcProtocol.KEY_CAPABILITIES).orEmpty().toSet(),
        )
    }
}

data class IpcIntentProbability(
    val name: String,
    val confidence: Double,
)

data class IpcAnalysisSection(
    val title: String,
    val options: List<IpcIntentProbability> = emptyList(),
    val text: String = "",
)

data class IpcAnalysisResult(
    val messageId: String,
    val conversationHash: String,
    val textHash: String,
    val isOutgoing: Boolean,
    val messageOccurrence: Int = 0,
    val emotion: String,
    val intents: List<IpcIntentProbability>,
    val riskLevel: Int,
    val suggestion: String,
    val detailSummary: String = "",
    val detailIntention: String = "",
    val detailContextual: Boolean = false,
    val detailEvidence: List<String> = emptyList(),
    val detailAction: String = "",
    val detailReply: String = "",
    val historyId: Long? = null,
    val sections: List<IpcAnalysisSection> = emptyList(),
)

@Parcelize
data class IpcVisibleChatMessage(
    val text: String,
    val isOutgoing: Boolean,
    val occurrence: Int,
    val localMessageId: Long? = null,
) : Parcelable

@Parcelize
data class IpcVisibleChatConversation(
    val conversationId: String,
    val conversationText: String,
    val anchorText: String,
    val anchorIsOutgoing: Boolean,
    val messages: List<IpcVisibleChatMessage>,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId)
        putString(KEY_CONVERSATION_TEXT, conversationText)
        putString(KEY_ANCHOR_TEXT, anchorText)
        putBoolean(KEY_ANCHOR_OUTGOING, anchorIsOutgoing)
        putParcelableArrayList(KEY_VISIBLE_MESSAGES, ArrayList(messages))
    }

    companion object {
        private const val KEY_CONVERSATION_TEXT = "visible_conversation_text"
        private const val KEY_ANCHOR_TEXT = "visible_anchor_text"
        private const val KEY_ANCHOR_OUTGOING = "visible_anchor_outgoing"
        private const val KEY_VISIBLE_MESSAGES = "visible_messages"

        fun fromBundle(bundle: Bundle): IpcVisibleChatConversation {
            bundle.classLoader = IpcVisibleChatMessage::class.java.classLoader
            return IpcVisibleChatConversation(
                conversationId = bundle.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
                conversationText = bundle.getString(KEY_CONVERSATION_TEXT).orEmpty(),
                anchorText = bundle.getString(KEY_ANCHOR_TEXT).orEmpty(),
                anchorIsOutgoing = bundle.getBoolean(KEY_ANCHOR_OUTGOING),
                messages = bundle.getParcelableArrayList<IpcVisibleChatMessage>(KEY_VISIBLE_MESSAGES).orEmpty(),
            )
        }
    }
}

enum class MessageSender {
    SELF,
    CONTACT,
    SYSTEM,
    UNKNOWN,
}

@Parcelize
data class CapturedMessage(
    val conversationId: String,
    val sender: MessageSender,
    val text: String,
    val timestampMs: Long,
    val sourcePackage: String,
    val sourceClass: String?,
    val isOutgoing: Boolean,
    val messageId: String?,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId)
        putString(IpcProtocol.KEY_SENDER, sender.name)
        putString(IpcProtocol.KEY_TEXT, text)
        putLong(IpcProtocol.KEY_TIMESTAMP_MS, timestampMs)
        putString(IpcProtocol.KEY_SOURCE_PACKAGE, sourcePackage)
        putString(IpcProtocol.KEY_SOURCE_CLASS, sourceClass)
        putBoolean(IpcProtocol.KEY_IS_OUTGOING, isOutgoing)
        putString(IpcProtocol.KEY_MESSAGE_ID, messageId)
    }

    companion object {
        fun fromBundle(bundle: Bundle): CapturedMessage = CapturedMessage(
            conversationId = bundle.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
            sender = bundle.getString(IpcProtocol.KEY_SENDER)
                ?.let { value -> runCatching { MessageSender.valueOf(value) }.getOrDefault(MessageSender.UNKNOWN) }
                ?: MessageSender.UNKNOWN,
            text = bundle.getString(IpcProtocol.KEY_TEXT).orEmpty(),
            timestampMs = bundle.getLong(IpcProtocol.KEY_TIMESTAMP_MS),
            sourcePackage = bundle.getString(IpcProtocol.KEY_SOURCE_PACKAGE).orEmpty(),
            sourceClass = bundle.getString(IpcProtocol.KEY_SOURCE_CLASS),
            isOutgoing = bundle.getBoolean(IpcProtocol.KEY_IS_OUTGOING),
            messageId = bundle.getString(IpcProtocol.KEY_MESSAGE_ID),
        )
    }
}

enum class RejectReason {
    DISABLED,
    AUTHENTICATION_REQUIRED,
    INVALID_TOKEN,
    PROTOCOL_MISMATCH,
    SOURCE_PACKAGE_NOT_ALLOWED,
    CALLER_PACKAGE_NOT_ALLOWED,
    BLANK_TEXT,
    TEXT_TOO_LONG,
    CONVERSATION_ID_REQUIRED,
    TIMESTAMP_INVALID,
    BATCH_TOO_LARGE,
    RATE_LIMITED,
}

data class ValidationResult(
    val accepted: Boolean,
    val reason: RejectReason? = null,
)

data class HandshakeResult(
    val accepted: Boolean,
    val reason: RejectReason? = null,
    val serverProtocolVersion: Int = IpcProtocol.VERSION,
)

data class SubmitResult(
    val accepted: Boolean,
    val reason: RejectReason? = null,
    val serverProtocolVersion: Int = IpcProtocol.VERSION,
)
