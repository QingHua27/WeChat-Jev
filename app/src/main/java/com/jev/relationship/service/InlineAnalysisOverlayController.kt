package com.jev.relationship.service

import com.jev.relationship.domain.inline.InlineAnalysisCard
import com.jev.relationship.domain.inline.InlineCardPlacement
import com.jev.relationship.domain.inline.InlineCardStore
import com.jev.relationship.domain.inline.InlineMessageLocator
import com.jev.relationship.domain.inline.InlineWindowSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

interface InlineOverlayHost {
    val isDragging: Boolean
        get() = false

    fun show(card: InlineAnalysisCard, placement: InlineCardPlacement)
    fun hide()
}

class InlineAnalysisOverlayController(
    private val cardStore: InlineCardStore,
    private val snapshotStore: InlineWindowSnapshotStore,
    private val host: InlineOverlayHost,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cardWidth: Int,
    private val cardHeight: Int,
    private val scope: CoroutineScope,
    private val missingPlacementGraceMs: Long = 600L,
) {
    private var renderJob: Job? = null
    private var missingPlacementJob: Job? = null

    fun start() {
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            combine(cardStore.state, snapshotStore.state) { card, snapshot -> card to snapshot }
                .collect { (card, snapshot) ->
                    val placement = if (card != null && snapshot != null) {
                        InlineMessageLocator.locate(
                            anchor = card.anchor,
                            nodes = snapshot.nodes,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            cardWidth = cardWidth,
                            cardHeight = cardHeight,
                        )
                    } else {
                        null
                    }
                    if (card == null) {
                        cancelMissingPlacementHide()
                        host.hide()
                    } else if (placement != null) {
                        cancelMissingPlacementHide()
                        host.show(card, placement)
                    } else if (!host.isDragging) {
                        scheduleMissingPlacementHide()
                    }
                }
        }
    }

    fun stop() {
        cancelMissingPlacementHide()
        renderJob?.cancel()
        renderJob = null
        host.hide()
    }

    private fun scheduleMissingPlacementHide() {
        if (missingPlacementJob?.isActive == true) return
        missingPlacementJob = scope.launch {
            delay(missingPlacementGraceMs)
            if (!host.isDragging) host.hide()
        }
    }

    private fun cancelMissingPlacementHide() {
        missingPlacementJob?.cancel()
        missingPlacementJob = null
    }

}
