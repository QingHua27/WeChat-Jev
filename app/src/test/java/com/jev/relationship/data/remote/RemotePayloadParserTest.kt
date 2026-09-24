package com.jev.relationship.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePayloadParserTest {
    @Test
    fun parsesReplyPayloadWithKnownTones() {
        val result = RemotePayloadParser.parseReplies(
            """{"replies":[{"tone":"gentle","text":"我在听"},{"tone":"serious","text":"我们聊聊"}]}""",
        )

        assertEquals(2, result.size)
        assertEquals("温柔", result[0].tone.label)
        assertEquals("我们聊聊", result[1].text)
    }
}
