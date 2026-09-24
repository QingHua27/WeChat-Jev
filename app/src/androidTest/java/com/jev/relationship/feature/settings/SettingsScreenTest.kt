package com.jev.relationship.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jev.relationship.data.settings.ProviderSettings
import com.jev.relationship.ui.theme.JevTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun simplifiedHomeKeepsModelConfigurationAccessibleAsSecondaryPage() {
        composeRule.setContent {
            JevTheme {
                SettingsScreen(
                    state = SettingsUiState(settings = ProviderSettings(apiKey = "test-key")),
                    onApiKeyChanged = {},
                    onReplyBaseUrlChanged = {},
                    onReplyApiKeyChanged = {},
                    onReplyModelChanged = {},
                    onRequestEnableRealtime = {},
                    onConfirmEnableRealtime = {},
                    onCancelEnableRealtime = {},
                    onDisableRealtime = {},
                    onSave = {},
                    onSelectPreset = {},
                    onSavePreset = {},
                    onDeletePreset = {},
                )
            }
        }

        composeRule.onNodeWithText("管理 Jev").assertIsDisplayed()
        composeRule.onNodeWithText("API Key").assertIsDisplayed()
        composeRule.onNodeWithText("模型选择").performScrollTo().performClick()
        composeRule.onNodeWithText("理解模型配置").assertIsDisplayed()
        composeRule.onNodeWithText("配置理解模型，用于对方消息生成详细回复").assertIsDisplayed()
        composeRule.onNodeWithText("API Base URL", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("API Key", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("模型名称", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("将当前配置保存为预设").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("管理 Jev").assertIsDisplayed()
    }
}
