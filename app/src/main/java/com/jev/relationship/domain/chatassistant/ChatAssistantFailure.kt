package com.jev.relationship.domain.chatassistant

class ChatAssistantFailure(
    val stage: Stage,
    cause: Throwable,
) : RuntimeException(cause) {
    enum class Stage {
        LOCAL_HISTORY,
        MODEL_CONFIGURATION,
        MODEL_REQUEST,
        SESSION_STORAGE,
    }
}
