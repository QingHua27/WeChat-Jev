package com.jev.relationship.service

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jev.relationship.domain.surface.AssistantSurfaceState
import com.jev.relationship.ui.theme.JevTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingAssistantPillTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clickingPillInvokesOpenAction() {
        var opened = false
        composeRule.setContent {
            JevTheme {
                FloatingAssistantPill(
                    state = AssistantSurfaceState.Ready,
                    onOpen = { opened = true },
                )
            }
        }

        composeRule.onNodeWithText("Jev 助手已开启").assertIsDisplayed().performClick()

        assertTrue(opened)
    }
}
