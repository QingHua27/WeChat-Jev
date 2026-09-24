package com.jev.relationship.domain.inline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InlineCardStore {
    private val _state = MutableStateFlow<InlineAnalysisCard?>(null)
    val state: StateFlow<InlineAnalysisCard?> = _state.asStateFlow()

    @Synchronized
    fun show(card: InlineAnalysisCard) {
        _state.value = card
    }

    @Synchronized
    fun clear(messageId: String? = null) {
        if (messageId == null || _state.value?.anchor?.messageId == messageId) {
            _state.value = null
        }
    }
}
