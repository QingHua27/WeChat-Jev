package com.jev.relationship.domain.inline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineMessageLocatorTest {
    private val anchor = InlineMessageAnchor(
        messageId = "message-1",
        conversationHash = "conversation-hash",
        textHash = "target-hash",
        isOutgoing = false,
    )

    @Test
    fun `places card below matching contact bubble`() {
        val placement = InlineMessageLocator.locate(
            anchor = anchor,
            nodes = listOf(node(textHash = "target-hash", left = 40, top = 500, right = 440, bottom = 620)),
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
        )

        assertEquals(InlineCardPlacement(left = 40, top = 636, width = 380, height = 260), placement)
    }

    @Test
    fun `shifts card above bubble when bottom would overflow`() {
        val placement = InlineMessageLocator.locate(
            anchor = anchor,
            nodes = listOf(node(textHash = "target-hash", left = 40, top = 1600, right = 440, bottom = 1760)),
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
        )

        assertEquals(InlineCardPlacement(left = 40, top = 1324, width = 380, height = 260), placement)
    }

    @Test
    fun `ignores nonmatching and nonchat nodes`() {
        val placement = InlineMessageLocator.locate(
            anchor = anchor,
            nodes = listOf(
                node(textHash = "other-hash", left = 40, top = 500, right = 440, bottom = 620),
                node(textHash = "target-hash", className = "android.widget.EditText", left = 40, top = 700, right = 1000, bottom = 820),
                node(textHash = "target-hash", visibleToUser = false, left = 40, top = 900, right = 440, bottom = 1020),
            ),
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
        )

        assertNull(placement)
    }

    @Test
    fun `clamps placement inside screen horizontally`() {
        val placement = InlineMessageLocator.locate(
            anchor = anchor,
            nodes = listOf(node(textHash = "target-hash", left = 900, top = 500, right = 1060, bottom = 620)),
            screenWidth = 1080,
            screenHeight = 1920,
            cardWidth = 380,
            cardHeight = 260,
        )

        assertEquals(InlineCardPlacement(left = 700, top = 636, width = 380, height = 260), placement)
    }

    private fun node(
        textHash: String,
        className: String = "android.widget.TextView",
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        visibleToUser: Boolean = true,
    ) = InlineNodeSnapshot(
        packageName = "com.tencent.mm",
        className = className,
        textHash = textHash,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        visibleToUser = visibleToUser,
    )
}
