package com.jev.relationship.ipc

object IpcProtocol {
    const val VERSION = 1
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val SERVICE_ACTION = "com.jev.relationship.action.BIND_IPC"
    const val MAX_TEXT_LENGTH = 4_000
    const val MAX_BATCH_SIZE = 20
    const val MAX_ANALYSIS_FIELD_LENGTH = 512
    const val MAX_INTENT_NAME_LENGTH = 128
    const val MAX_ANALYSIS_INTENTS = 8
    const val MAX_DETAIL_EVIDENCE = 3
    const val MAX_ANALYSIS_SECTIONS = 4
    const val KEY_ANALYSIS_SECTIONS = "analysis_sections"
    const val MAX_MESSAGE_ID_LENGTH = 256
    const val MAX_HASH_LENGTH = 128
    const val CHAT_ASSISTANT_TIMEOUT_MS = 180_000L

    const val MSG_HELLO = 1
    const val MSG_SUBMIT = 2
    const val MSG_DISCONNECT = 3
    const val MSG_HISTORY_PAGE = 4
    const val MSG_HISTORY_INVALIDATED = 5
    const val MSG_CONVERSATION_TOGGLE = 6
    const val MSG_VISIBLE_CHAT = 7
    const val MSG_CHAT_ASSISTANT_REQUEST = 8
    const val MSG_CHAT_ASSISTANT_CANCEL = 9
    const val MSG_REGENERATE_RECENT = 10
    const val MSG_HANDSHAKE_RESULT = 101
    const val MSG_SUBMIT_RESULT = 102
    const val MSG_ANALYSIS_RESULT = 103
    const val MSG_ANALYSIS_CLEAR = 104
    const val MSG_HISTORY_REQUEST = 105
    const val MSG_CHAT_ASSISTANT_RESULT = 106
    const val MSG_CHAT_ASSISTANT_PROGRESS = 107
    const val MSG_REGENERATE_STATUS = 108
    const val MSG_REPLY_SUGGESTION = 109

    const val KEY_PROTOCOL_VERSION = "protocol_version"
    const val KEY_PAIRING_TOKEN = "pairing_token"
    const val KEY_SOURCE_PACKAGE = "source_package"
    const val KEY_MODULE_VERSION = "module_version"
    const val KEY_CONVERSATION_ID = "conversation_id"
    const val KEY_SENDER = "sender"
    const val KEY_TEXT = "text"
    const val KEY_TIMESTAMP_MS = "timestamp_ms"
    const val KEY_SOURCE_CLASS = "source_class"
    const val KEY_IS_OUTGOING = "is_outgoing"
    const val KEY_MESSAGE_ID = "message_id"
    const val KEY_ACCEPTED = "accepted"
    const val KEY_REASON = "reason"
    const val KEY_SERVER_PROTOCOL_VERSION = "server_protocol_version"
    const val KEY_CAPABILITIES = "capabilities"
    const val KEY_EMOTION = "emotion"
    const val KEY_INTENT_NAMES = "intent_names"
    const val KEY_INTENT_CONFIDENCES = "intent_confidences"
    const val KEY_RISK_LEVEL = "risk_level"
    const val KEY_SUGGESTION = "suggestion"
    const val KEY_DETAIL_SUMMARY = "detail_summary"
    const val KEY_DETAIL_INTENTION = "detail_intention"
    const val KEY_DETAIL_CONTEXTUAL = "detail_contextual"
    const val KEY_DETAIL_EVIDENCE = "detail_evidence"
    const val KEY_DETAIL_ACTION = "detail_action"
    const val KEY_DETAIL_REPLY = "detail_reply"
    const val KEY_HISTORY_ID = "history_id"
    const val KEY_MESSAGE_OCCURRENCE = "message_occurrence"
    const val KEY_CONVERSATION_TITLE = "conversation_title"
    const val KEY_ANALYSIS_ENABLED = "analysis_enabled"
    const val KEY_ASSISTANT_REQUEST_ID = "assistant_request_id"
    const val KEY_ASSISTANT_QUESTION = "assistant_question"
    const val KEY_ASSISTANT_RESUME_SESSION = "assistant_resume_session"
    const val KEY_ASSISTANT_TURNS = "assistant_turns"
    const val KEY_ASSISTANT_ERROR = "assistant_error"
}

object IpcCapabilities {
    const val EMBEDDED_CHAT_CARD = "embedded_chat_card"
    const val LOCAL_HISTORY = "local_history"
}
