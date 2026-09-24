package com.jev.relationship.domain.source

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityMessageParserTest {
    @Test
    fun parserKeepsVisibleChatTextInOrderAndRemovesDuplicates() {
        val result = AccessibilityMessageParser.parse(
            listOf(
                AccessibilityTextNode("com.tencent.mm", "你：还好吗？", true),
                AccessibilityTextNode("com.tencent.mm", "她：忙", true),
                AccessibilityTextNode("com.tencent.mm", "她：忙", true),
                AccessibilityTextNode("com.tencent.mm", "", true),
                AccessibilityTextNode("com.android.systemui", "通知", true),
            ),
            allowedPackages = setOf("com.tencent.mm"),
        )

        assertEquals("你：还好吗？\n她：忙", result)
    }

    @Test
    fun parserRejectsNodesFromUnknownPackages() {
        val result = AccessibilityMessageParser.parse(
            listOf(AccessibilityTextNode("com.example.other", "私人消息", true)),
            allowedPackages = setOf("com.tencent.mm"),
        )

        assertEquals(null, result)
    }
}

