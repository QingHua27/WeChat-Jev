package com.jev.relationship.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedAnalysisPayloadParserTest {
    @Test
    fun parsesDetailedUnderstandingPayload() {
        val result = DetailedAnalysisPayloadParser.parse(
            """{
                "summary":"对方在追问你为什么没有接电话。",
                "intention":"希望得到回应和解释，而不是单纯闲聊。",
                "evidence":["连续追问未接电话","语气带有明显的不满"],
                "action":"先回应未接电话这件事，再说明原因。",
                "reply":"刚才没看到电话，不是故意不接，我现在在听。"
            }""".trimIndent(),
        )

        assertEquals("对方在追问你为什么没有接电话。", result.summary)
        assertEquals("希望得到回应和解释，而不是单纯闲聊。", result.intention)
        assertEquals(listOf("连续追问未接电话", "语气带有明显的不满"), result.evidence)
        assertEquals("先回应未接电话这件事，再说明原因。", result.action)
        assertEquals("刚才没看到电话，不是故意不接，我现在在听。", result.reply)
    }

    @Test
    fun rejectsIncompleteDetailedUnderstandingPayload() {
        val error = runCatching {
            DetailedAnalysisPayloadParser.parse("{\"summary\":\"只有摘要\"}")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun acceptsAnIntentionOnlyResponseFromTheUnderstandingModel() {
        val result = DetailedAnalysisPayloadParser.parse("{\"intention\":\"她希望你给出明确答复。\"}")

        assertEquals("她希望你给出明确答复。", result.intention)
        assertEquals(result.intention, result.summary)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun acceptsPlainTextWhenTheModelDoesNotWrapItsIntentionInJson() {
        val result = DetailedAnalysisPayloadParser.parse("她是在提醒你兑现之前的约定。")

        assertEquals("她是在提醒你兑现之前的约定。", result.intention)
    }

    @Test
    fun acceptsJsonWrappedInMarkdownFence() {
        val result = DetailedAnalysisPayloadParser.parse(
            """```json
            {"summary":"摘要","intention":"诉求","evidence":["依据"],"action":"行动","reply":"回复"}
            ```""".trimIndent(),
        )

        assertEquals("摘要", result.summary)
    }
}
