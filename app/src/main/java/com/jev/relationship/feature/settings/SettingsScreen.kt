package com.jev.relationship.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBaseUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onReplyBaseUrlChanged: (String) -> Unit,
    onReplyApiKeyChanged: (String) -> Unit,
    onReplyModelChanged: (String) -> Unit,
    onEnableXposedIntegration: () -> Unit,
    onCopyXposedPairingToken: (String) -> Unit,
    onRotateXposedPairingToken: () -> Unit,
    onDisableXposedIntegration: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartFloatingAssistant: () -> Unit,
    onStopFloatingAssistant: () -> Unit,
    overlayPermissionGranted: Boolean,
    onRequestEnableRealtime: () -> Unit,
    onConfirmEnableRealtime: () -> Unit,
    onCancelEnableRealtime: () -> Unit,
    onDisableRealtime: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 接口设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Jev 用于结构化分析；未配置时应用会使用本地演示分析。回复生成接口为可选的 OpenAI-compatible 兼容接口。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.settings.baseUrl,
                onValueChange = onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.typesafe.ai/v1/") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.settings.apiKey,
                onValueChange = onApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Text("可选：回复生成模型", style = MaterialTheme.typography.titleMedium)
            Text(
                "Jev 负责结构化判断；如果希望由大语言模型生成多条回复，请单独配置一个 OpenAI-compatible 接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.settings.replyBaseUrl,
                onValueChange = onReplyBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("回复 API Base URL") },
                placeholder = { Text("https://api.openai.com/v1/") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.settings.replyApiKey,
                onValueChange = onReplyApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("回复 API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.settings.replyModel,
                onValueChange = onReplyModelChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("回复模型名称") },
                placeholder = { Text("gpt-4.1-mini") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.settings.model,
                onValueChange = onModelChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Jev 模型名称") },
                singleLine = true,
            )
            Text(
                text = "Key 会使用 Android Keystore 加密后保存。聊天内容只会在确认后发送。Jev 官方模型名为 jev-latest。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
            Text("可选：悬浮助手", style = MaterialTheme.typography.titleMedium)
            Text(
                "无障碍只读取当前允许的聊天应用可见文本；悬浮窗仅显示分析入口，不会自动发送消息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                Text("管理无障碍权限")
            }
            OutlinedButton(onClick = onStartFloatingAssistant, modifier = Modifier.fillMaxWidth()) {
                Text("启动悬浮助手")
            }
            TextButton(onClick = onStopFloatingAssistant, modifier = Modifier.fillMaxWidth()) {
                Text("关闭悬浮助手")
            }
            Text("实时分析助手", style = MaterialTheme.typography.titleMedium)
            Text(
                "默认关闭。开启后，仅在你确认并完成相应权限设置时，对已允许的聊天内容进行只读分析；不会自动回复或发送消息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (state.realtime.enabled) "实时分析已开启" else "实时分析已关闭")
                Switch(
                    checked = state.realtime.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled) onRequestEnableRealtime() else onDisableRealtime()
                    },
                )
            }
            if (state.realtime.enabled && !overlayPermissionGranted) {
                Text(
                    "实时分析需要悬浮窗权限。请先开启权限，再由你手动启动悬浮助手；开启实时分析不会自动启动服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onStartFloatingAssistant, modifier = Modifier.fillMaxWidth()) {
                    Text("去开启悬浮窗权限")
                }
            }
            Text("LSPosed/Xposed 接入", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅在你主动启用并完成配对后读取微信消息。模块不会保存 Jev/API Key，也不会自动发送消息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.xposed.enabled && state.xposed.pairingToken != null) {
                var pairingTokenCopied by remember(state.xposed.pairingToken) { mutableStateOf(false) }
                Text("配对令牌（输入到独立 Xposed 模块配置中）", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.xposed.pairingToken,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        onCopyXposedPairingToken(
                            PairingTokenClipboard.normalize(state.xposed.pairingToken),
                        )
                        pairingTokenCopied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pairingTokenCopied) "已复制配对令牌" else "复制配对令牌")
                }
                OutlinedButton(onClick = onRotateXposedPairingToken, modifier = Modifier.fillMaxWidth()) {
                    Text("轮换配对令牌")
                }
                TextButton(onClick = onDisableXposedIntegration, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭并撤销配对")
                }
            } else {
                OutlinedButton(onClick = onEnableXposedIntegration, modifier = Modifier.fillMaxWidth()) {
                    Text("生成令牌并启用 Xposed 接入")
                }
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saved) "已保存" else "保存设置")
            }
        }
        if (state.showRealtimeConsent) {
            AlertDialog(
                onDismissRequest = onCancelEnableRealtime,
                title = { Text("确认开启实时分析？") },
                text = {
                    Text(
                        "开启后，已授权聊天应用中的消息会先在本地短暂聚合，再用于生成只读关系分析。配置 Jev API Key 后会发送到对应接口；未配置时使用本地演示分析。Jev 不会自动回复或发送消息，悬浮助手也需要你手动启动。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmEnableRealtime) { Text("确认开启") }
                },
                dismissButton = {
                    TextButton(onClick = onCancelEnableRealtime) { Text("暂不开启") }
                },
            )
        }
    }
}
