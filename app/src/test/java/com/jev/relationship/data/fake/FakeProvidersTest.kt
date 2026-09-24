package com.jev.relationship.data.fake

import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.ReplyTone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeProvidersTest {
    @Test
    fun fakeAnalyzerReturnsStructuredRelationshipSignals() = runTest {
        val result = FakeJevAnalyzer().analyze(Conversation("你是不是又忘了我说过的事？"))

        assertEquals("不满", result.emotion)
        assertEquals(8, result.riskLevel)
        assertTrue(result.intents.any { it.name == "希望被重视" })
    }

    @Test
    fun fakeReplyGeneratorReturnsThreeDistinctTones() = runTest {
        val replies = FakeReplyGenerator().generate(
            conversation = Conversation("她：忙"),
            analysis = FakeJevAnalyzer().analyze(Conversation("她：忙")),
        )

        assertEquals(3, replies.size)
        assertEquals(setOf(ReplyTone.Gentle, ReplyTone.Humorous, ReplyTone.Serious), replies.map { it.tone }.toSet())
    }
}

