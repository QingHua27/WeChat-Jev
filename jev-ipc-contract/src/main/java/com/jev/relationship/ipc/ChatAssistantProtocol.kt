package com.jev.relationship.ipc

import android.os.Bundle

data class ChatAssistantRequest(
    val requestId: String,
    val conversationId: String,
    val title: String,
    val question: String? = null,
    val resumeSession: Boolean = false,
    val fullContext: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        validate()
        putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, requestId)
        putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId)
        putString(IpcProtocol.KEY_CONVERSATION_TITLE, title)
        putString(IpcProtocol.KEY_ASSISTANT_QUESTION, question)
        putBoolean(IpcProtocol.KEY_ASSISTANT_RESUME_SESSION, resumeSession)
        putBoolean("assistant_full_context", fullContext)
    }

    private fun validate() {
        require(requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(conversationId.isNotBlank() && conversationId.length <= MAX_CONVERSATION_ID_LENGTH)
        require(title.length <= MAX_TITLE_LENGTH)
        require(question == null || (question.isNotBlank() && question.length <= MAX_QUESTION_LENGTH))
        require(!resumeSession || question == null)
    }

    companion object {
        const val MAX_REQUEST_ID_LENGTH = 80
        const val MAX_CONVERSATION_ID_LENGTH = 256
        const val MAX_TITLE_LENGTH = 256
        const val MAX_QUESTION_LENGTH = IpcProtocol.MAX_TEXT_LENGTH
        const val DEFAULT_ANALYSIS_PROMPT = "请基于最新完整聊天记录生成整体分析：只回复“收到”，再用不超过3句、100字说明你理解的核心意思，最后问“你有什么疑问？”。不要展开分析、列证据或给建议；群聊请区分成员，不确定时简短说明。"
        val SAVING_ANALYSIS_PROMPT = DEFAULT_ANALYSIS_PROMPT.replace("最新完整聊天记录", "本次提供的聊天片段")

        fun fromBundle(bundle: Bundle): ChatAssistantRequest = ChatAssistantRequest(
            requestId = bundle.getString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID).orEmpty(),
            conversationId = bundle.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
            title = bundle.getString(IpcProtocol.KEY_CONVERSATION_TITLE).orEmpty(),
            question = bundle.getString(IpcProtocol.KEY_ASSISTANT_QUESTION),
            resumeSession = bundle.getBoolean(IpcProtocol.KEY_ASSISTANT_RESUME_SESSION),
            fullContext = bundle.getBoolean("assistant_full_context"),
        ).also(ChatAssistantRequest::validate)

        fun validationFailure(bundle: Bundle): ChatAssistantResult? {
            val requestId = bundle.getString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID).orEmpty()
            val conversationId = bundle.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty()
            if (requestId.length !in 1..MAX_REQUEST_ID_LENGTH) return null
            if (conversationId.isBlank() || conversationId.length > MAX_CONVERSATION_ID_LENGTH) return null
            return ChatAssistantResult(
                requestId = requestId,
                conversationId = conversationId,
                turns = emptyList(),
                error = "分析请求格式无效，请重试。",
            )
        }
    }
}

data class ChatAssistantTurn(
    val role: String,
    val content: String,
    val createdAtMs: Long,
) {
    fun validate() {
        require(role == ROLE_USER || role == ROLE_ASSISTANT)
        require(content.isNotBlank() && content.length <= MAX_CONTENT_LENGTH)
        require(createdAtMs >= 0)
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val MAX_CONTENT_LENGTH = 64_000
    }
}

data class ChatAssistantResult(
    val requestId: String,
    val conversationId: String,
    val turns: List<ChatAssistantTurn>,
    val error: String? = null,
    val olderTurnsOmitted: Boolean = false,
    val isPartial: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        validate()
        putString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID, requestId)
        putString(IpcProtocol.KEY_CONVERSATION_ID, conversationId)
        putParcelableArrayList(IpcProtocol.KEY_ASSISTANT_TURNS, ArrayList(turns.map { turn ->
            Bundle().apply {
                putString(KEY_ROLE, turn.role)
                putString(KEY_CONTENT, turn.content)
                putLong(KEY_CREATED_AT, turn.createdAtMs)
            }
        }))
        putString(IpcProtocol.KEY_ASSISTANT_ERROR, error)
        putBoolean(KEY_OLDER_TURNS_OMITTED, olderTurnsOmitted)
        putBoolean(KEY_IS_PARTIAL, isPartial)
    }

    private fun validate() {
        require(requestId.length in 1..ChatAssistantRequest.MAX_REQUEST_ID_LENGTH)
        require(conversationId.isNotBlank() && conversationId.length <= ChatAssistantRequest.MAX_CONVERSATION_ID_LENGTH)
        require(turns.size <= MAX_TURNS)
        require((error?.length ?: 0) <= MAX_ERROR_LENGTH)
        turns.forEach(ChatAssistantTurn::validate)
        require(turns.sumOf { it.content.length } <= MAX_TOTAL_CONTENT_LENGTH)
        require(error == null || turns.isEmpty())
        require(error == null || !olderTurnsOmitted)
        require(!isPartial || (error == null && !olderTurnsOmitted &&
            turns.size == 1 && turns.single().role == ChatAssistantTurn.ROLE_ASSISTANT))
    }

    companion object {
        const val MAX_TURNS = 200
        const val MAX_ERROR_LENGTH = 256
        const val MAX_TOTAL_CONTENT_LENGTH = 200_000
        private const val KEY_ROLE = "role"
        private const val KEY_CONTENT = "content"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_OLDER_TURNS_OMITTED = "assistant_older_turns_omitted"
        private const val KEY_IS_PARTIAL = "assistant_is_partial"

        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle): ChatAssistantResult {
            val rawTurns = bundle.getParcelableArrayList<Bundle>(IpcProtocol.KEY_ASSISTANT_TURNS).orEmpty()
            require(rawTurns.size <= MAX_TURNS)
            val result = ChatAssistantResult(
                requestId = bundle.getString(IpcProtocol.KEY_ASSISTANT_REQUEST_ID).orEmpty(),
                conversationId = bundle.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
                turns = rawTurns.map { item ->
                    ChatAssistantTurn(
                        role = item.getString(KEY_ROLE).orEmpty(),
                        content = item.getString(KEY_CONTENT).orEmpty(),
                        createdAtMs = item.getLong(KEY_CREATED_AT),
                    )
                },
                error = bundle.getString(IpcProtocol.KEY_ASSISTANT_ERROR),
                olderTurnsOmitted = bundle.getBoolean(KEY_OLDER_TURNS_OMITTED),
                isPartial = bundle.getBoolean(KEY_IS_PARTIAL),
            )
            result.validate()
            return result
        }

        fun forDisplay(requestId: String, conversationId: String, turns: List<ChatAssistantTurn>): ChatAssistantResult {
            val visible = ArrayDeque<ChatAssistantTurn>()
            var chars = 0
            for (turn in turns.asReversed()) {
                turn.validate()
                if (visible.size >= MAX_TURNS || chars + turn.content.length > MAX_TOTAL_CONTENT_LENGTH) break
                visible.addFirst(turn)
                chars += turn.content.length
            }
            return ChatAssistantResult(
                requestId = requestId,
                conversationId = conversationId,
                turns = visible.toList(),
                olderTurnsOmitted = visible.size < turns.size,
            )
        }
    }
}
