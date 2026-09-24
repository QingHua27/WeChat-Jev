package com.jev.relationship.domain.realtime

import com.jev.relationship.domain.AnalysisOutput

sealed interface RealtimeAnalysisState {
    data object Disabled : RealtimeAnalysisState
    data object WaitingForPermission : RealtimeAnalysisState
    data class Collecting(
        val conversationId: String,
        val messageId: String? = null,
    ) : RealtimeAnalysisState
    data class Analyzing(
        val conversationId: String,
        val generation: Long,
        val messageId: String? = null,
    ) : RealtimeAnalysisState
    data class Completed(
        val conversationId: String,
        val output: AnalysisOutput,
        val historyId: Long?,
        val historyError: String? = null,
        val messageId: String? = null,
    ) : RealtimeAnalysisState
    data class Error(
        val conversationId: String,
        val message: String,
        val messageId: String? = null,
    ) : RealtimeAnalysisState
}
