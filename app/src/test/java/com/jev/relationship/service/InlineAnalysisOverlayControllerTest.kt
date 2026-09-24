package com.jev.relationship.service

import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.domain.inline.InlineAnalysisCard
import com.jev.relationship.domain.inline.InlineCardStore
import com.jev.relationship.domain.inline.InlineMessageAnchor
import com.jev.relationship.domain.inline.InlineNodeSnapshot
import com.jev.relationship.domain.inline.InlineWindowSnapshot
import com.jev.relationship.domain.inline.InlineWindowSnapshotStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InlineAnalysisOverlayControllerTest {
    @Test
    fun `matching card and snapshot are rendered at located placement`() = runTest {
        val cardStore = InlineCardStore()
        val snapshotStore = InlineWindowSnapshotStore()
        val host = RecordingHost()
        val controller = InlineAnalysisOverlayController(
            cardStore = cardStore,
            snapshotStore = snapshotStore,
            host = host,
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()
        cardStore.show(card())
        snapshotStore.publish(snapshot())
        runCurrent()
        advanceUntilIdle()

        assertTrue(host.visible)
        assertEquals(40, host.left)
        assertEquals(636, host.top)
    }

    @Test
    fun `missing match hides the card`() = runTest {
        val cardStore = InlineCardStore()
        val snapshotStore = InlineWindowSnapshotStore()
        val host = RecordingHost()
        val controller = InlineAnalysisOverlayController(
            cardStore = cardStore,
            snapshotStore = snapshotStore,
            host = host,
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
            scope = backgroundScope,
        )
        controller.start()
        cardStore.show(card())
        snapshotStore.publish(snapshot(textHash = "other"))
        advanceUntilIdle()

        assertFalse(host.visible)
    }

    @Test
    fun `temporary missing match does not immediately remove visible card`() = runTest {
        val cardStore = InlineCardStore()
        val snapshotStore = InlineWindowSnapshotStore()
        val host = RecordingHost()
        val controller = InlineAnalysisOverlayController(
            cardStore = cardStore,
            snapshotStore = snapshotStore,
            host = host,
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
            scope = backgroundScope,
            missingPlacementGraceMs = 600L,
        )
        controller.start()
        cardStore.show(card())
        snapshotStore.publish(snapshot())
        runCurrent()
        assertTrue(host.visible)

        snapshotStore.publish(snapshot(textHash = "temporary-miss"))
        runCurrent()
        assertTrue(host.visible)

        snapshotStore.publish(snapshot())
        advanceUntilIdle()
        assertTrue(host.visible)
        assertEquals(0, host.hideCount)
    }

    @Test
    fun `stop hides and cancels overlay updates`() = runTest {
        val cardStore = InlineCardStore()
        val snapshotStore = InlineWindowSnapshotStore()
        val host = RecordingHost()
        val controller = InlineAnalysisOverlayController(
            cardStore = cardStore,
            snapshotStore = snapshotStore,
            host = host,
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
            scope = backgroundScope,
        )
        controller.start()
        cardStore.show(card())
        snapshotStore.publish(snapshot())
        advanceUntilIdle()
        controller.stop()
        cardStore.clear()
        advanceUntilIdle()

        assertFalse(host.visible)
        assertEquals(1, host.hideCount)
    }

    private class RecordingHost : InlineOverlayHost {
        var visible = false
        var left = 0
        var top = 0
        var hideCount = 0

        override fun show(card: InlineAnalysisCard, placement: com.jev.relationship.domain.inline.InlineCardPlacement) {
            visible = true
            left = placement.left
            top = placement.top
        }

        override fun hide() {
            visible = false
            hideCount += 1
        }
    }

    private fun card() = InlineAnalysisCard(
        anchor = InlineMessageAnchor("message-1", "conversation", "target", false),
        output = AnalysisOutput(
            analysis = AnalysisResult("平静", listOf(IntentProbability("回应", 0.8)), 2, "先回应"),
            replies = emptyList(),
        ),
        updatedAt = 1L,
    )

    private fun snapshot(textHash: String = "target") = InlineWindowSnapshot(
        packageName = "com.tencent.mm",
        screenWidth = 1080,
        screenHeight = 1920,
        timestampMs = 1L,
        nodes = listOf(
            InlineNodeSnapshot("com.tencent.mm", "android.widget.TextView", textHash, 40, 500, 440, 620, true),
        ),
    )
}
