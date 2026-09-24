package com.jev.relationship.service

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jev.relationship.core.model.AnalysisResult
import com.jev.relationship.core.model.DetailedAnalysis
import com.jev.relationship.core.model.IntentProbability
import com.jev.relationship.domain.AnalysisOutput
import com.jev.relationship.domain.inline.InlineAnalysisCard
import com.jev.relationship.domain.inline.InlineMessageAnchor
import com.jev.relationship.ui.theme.JevTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineAnalysisCardViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersAnalysisSummaryAndOpensDetails() {
        var opened = false
        composeRule.setContent {
            JevTheme {
                InlineAnalysisCardView(
                    card = card(),
                    onOpen = { opened = true },
                )
            }
        }

        composeRule.onNodeWithText("Jev").assertIsDisplayed()
        composeRule.onNodeWithText("对方在认真表达").assertIsDisplayed()
        composeRule.onNodeWithText("依据：消息内容").assertIsDisplayed()
        composeRule.onNodeWithText("建议行动：先回应").assertIsDisplayed()
        composeRule.onNodeWithTag("inline_analysis_card").performClick()

        assertTrue(opened)
    }

    private fun card() = InlineAnalysisCard(
        anchor = InlineMessageAnchor("message-1", "conversation", "target", false),
        output = AnalysisOutput(
            analysis = AnalysisResult(
                "平静",
                listOf(IntentProbability("回应", 0.8)),
                2,
                "先回应",
                DetailedAnalysis(
                    summary = "对方在认真表达",
                    intention = "希望得到回应",
                    evidence = listOf("消息内容"),
                    action = "先回应",
                    reply = "我在听",
                ),
            ),
            replies = emptyList(),
        ),
        updatedAt = 1L,
    )
}
