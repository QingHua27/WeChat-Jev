package com.jev.relationship.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcAnalysisResultCodecTest {
    @Test
    fun `analysis result survives bundle round trip without raw message text`() {
        val result = IpcAnalysisResult(
            messageId = "message-42",
            conversationHash = "conversation-hash",
            textHash = "text-hash",
            isOutgoing = false,
            emotion = "谨慎",
            intents = listOf(
                IpcIntentProbability("确认边界", 0.72),
                IpcIntentProbability("寻求回应", 0.18),
            ),
            riskLevel = 7,
            suggestion = "先确认对方真正想解决的问题。",
            detailSummary = "对方在认真表达自己的担忧。",
            detailIntention = "希望得到明确回应。",
            detailContextual = true,
            detailEvidence = listOf("连续追问", "语气变得急切"),
            detailAction = "先回应问题本身。",
            detailReply = "我看到了，我们把这件事说清楚。",
            sections = listOf(IpcAnalysisSection("危机是否解除？", listOf(IpcIntentProbability("Yes", 0.94), IpcIntentProbability("No", 0.06))), IpcAnalysisSection("建议动作", text = "停止继续解释。")),
            historyId = 19L,
        )

        val decoded = IpcCodec.decodeAnalysisResult(IpcCodec.encodeAnalysisResult(result))

        assertEquals(result, decoded)
    }

    @Test
    fun `hello round trip preserves embedded card capability`() {
        val hello = IpcHello(
            protocolVersion = IpcProtocol.VERSION,
            pairingToken = "pairing-token",
            sourcePackage = IpcProtocol.WECHAT_PACKAGE,
            moduleVersion = "0.2.0",
            capabilities = setOf(IpcCapabilities.EMBEDDED_CHAT_CARD),
        )

        val decoded = IpcHello.fromBundle(hello.toBundle())

        assertEquals(hello.capabilities, decoded.capabilities)
    }

    @Test
    fun `rejects non finite section probability`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcCodec.encodeAnalysisResult(validResult().copy(sections = listOf(
                IpcAnalysisSection("问题", listOf(IpcIntentProbability("Yes", Double.NaN))),
            )))
        }
    }

    @Test
    fun `analysis result rejects blank message id`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcCodec.encodeAnalysisResult(validResult(messageId = " "))
        }
    }

    @Test
    fun `analysis result rejects oversized display fields and invalid risk`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcCodec.encodeAnalysisResult(
                validResult(emotion = "x".repeat(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH + 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpcCodec.encodeAnalysisResult(validResult(riskLevel = 11))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpcCodec.encodeAnalysisResult(
                validResult(
                    intents = List(IpcProtocol.MAX_ANALYSIS_INTENTS + 1) {
                        IpcIntentProbability("意图$it", 0.1)
                    },
                ),
            )
        }
    }

    private fun validResult(
        messageId: String = "message-1",
        emotion: String = "平静",
        intents: List<IpcIntentProbability> = listOf(IpcIntentProbability("沟通", 0.8)),
        riskLevel: Int = 3,
    ) = IpcAnalysisResult(
        messageId = messageId,
        conversationHash = "conversation-hash",
        textHash = "text-hash",
        isOutgoing = false,
        emotion = emotion,
        intents = intents,
        riskLevel = riskLevel,
        suggestion = "保持清晰。",
    )
}
