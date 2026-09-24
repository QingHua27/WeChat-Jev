package com.jev.relationship.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onApiKeyChanged: (String) -> Unit,
    onReplyBaseUrlChanged: (String) -> Unit,
    onReplyApiKeyChanged: (String) -> Unit,
    onReplyModelChanged: (String) -> Unit,
    onRequestEnableRealtime: () -> Unit,
    onConfirmEnableRealtime: () -> Unit,
    onCancelEnableRealtime: () -> Unit,
    onDisableRealtime: () -> Unit,
    onSave: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
) {
    var showReplyModelSettings by rememberSaveable { mutableStateOf(false) }
    var showTokenUsage by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    BackHandler(enabled = !showReplyModelSettings && !showTokenUsage) { activity?.finish() }
    BackHandler(enabled = showReplyModelSettings) { showReplyModelSettings = false }
    BackHandler(enabled = showTokenUsage) { showTokenUsage = false }

    if (showTokenUsage) {
        com.jev.relationship.feature.usage.TokenUsageRoute(onBack = { showTokenUsage = false })
        return
    }

    if (showReplyModelSettings) {
        ReplySettingsScreen(
            state = state,
            onBack = { showReplyModelSettings = false },
            onBaseUrlChanged = onReplyBaseUrlChanged,
            onApiKeyChanged = onReplyApiKeyChanged,
            onModelChanged = onReplyModelChanged,
            onSave = onSave,
            onSelectPreset = onSelectPreset,
            onSavePreset = onSavePreset,
            onDeletePreset = onDeletePreset,
        )
        return
    }

    SettingsHomeScreen(
        state = state,
        onApiKeyChanged = onApiKeyChanged,
        onOpenReplyModelSettings = { showReplyModelSettings = true },
        onOpenTokenUsage = { showTokenUsage = true },
        onRequestEnableRealtime = onRequestEnableRealtime,
        onConfirmEnableRealtime = onConfirmEnableRealtime,
        onCancelEnableRealtime = onCancelEnableRealtime,
        onDisableRealtime = onDisableRealtime,
        onSelectPreset = onSelectPreset,
        onSave = onSave,
    )
}

@Composable
private fun SettingsHomeScreen(
    state: SettingsUiState,
    onApiKeyChanged: (String) -> Unit,
    onOpenReplyModelSettings: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    onRequestEnableRealtime: () -> Unit,
    onConfirmEnableRealtime: () -> Unit,
    onCancelEnableRealtime: () -> Unit,
    onDisableRealtime: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    var showJevKey by rememberSaveable { mutableStateOf(false) }
    // A popup is transient UI state. Do not restore it after Activity recreation.
    var presetMenuExpanded by remember { mutableStateOf(false) }
    val activeReplySettings = remember(state.settings.replyBaseUrl, state.settings.replyApiKey, state.settings.replyModel) {
        runCatching {
            state.settings.replySettings().let { it.copy(baseUrl = it.normalizedBaseUrl()) }
        }.getOrNull()
    }
    val activePreset = state.replyModelPresets.firstOrNull { it.settings == activeReplySettings }
    val cardShape = RoundedCornerShape(24.dp)

    Scaffold(
        containerColor = PAGE_BACKGROUND,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PAGE_BACKGROUND)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 66.dp),
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp, max = 44.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(BLUE_START, BLUE_END))),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                ) {
                    Text("▣   ${if (state.saved) "设置已保存" else "保存设置"}")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 0.dp)
                .offset(y = (-8).dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { context.findActivity()?.finish() },
                modifier = Modifier.size(40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("‹", color = Color(0xFF17233C), style = MaterialTheme.typography.headlineMedium) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("管理 Jev", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp, lineHeight = 30.sp), fontWeight = FontWeight.Bold, color = Color(0xFF17233C))
                    Text("让微信对话分析更智能、更安全", style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Color(0xFF17233C))
                    Text("只需简单配置，即可开始使用", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Color(0xFF8392B0))
                }
                Box(modifier = Modifier.padding(end = 60.dp)) { JevRobotMark() }
            }

            SettingsCard(shape = cardShape, cardPadding = 6.dp, itemSpacing = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(symbol = "⚒", tint = Color(0xFF2878E5), background = Color(0xFFEAF2FF))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("API Key", style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp), fontWeight = FontWeight.SemiBold)
                        Text("配置 Jev 的访问密钥", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp), color = SECONDARY_TEXT)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 48.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("▣", color = SECONDARY_TEXT, style = MaterialTheme.typography.titleSmall)
                            BasicTextField(
                                value = state.settings.apiKey,
                                onValueChange = onApiKeyChanged,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF17233C)),
                                visualTransformation = if (showJevKey) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                            )
                            TextButton(
                                onClick = { showJevKey = !showJevKey },
                                modifier = Modifier.semantics { contentDescription = if (showJevKey) "隐藏 API Key" else "显示 API Key" },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) { Text(if (showJevKey) "隐藏" else "◉", color = SECONDARY_TEXT) }
                        }
                    }
                    Text(
                        "Jev API Key",
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 42.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = SECONDARY_TEXT,
                    )
                }

            }

            Spacer(Modifier.size(4.dp))

            SettingsCard(shape = cardShape, cardPadding = 7.dp, itemSpacing = 5.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onOpenReplyModelSettings)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconBadge(symbol = "⬡", tint = Color(0xFF8054D8), background = Color(0xFFF0EAFE))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("模型选择", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp), fontWeight = FontWeight.SemiBold)
                        Text("用于为对方消息生成简短解析", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = SECONDARY_TEXT)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(32.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { presetMenuExpanded = true },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFFCFDFF),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BORDER),
                        ) {
                            Row(
                                modifier = Modifier.heightIn(min = 36.dp, max = 36.dp).padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    activePreset?.settings?.model
                                        ?: state.settings.replyModel.ifBlank { "选择理解模型" },
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                                )
                                Text("⌄", color = Color(0xFF7F8BA2), style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp))
                            }
                        }
                        SettingsDropdownMenu(
                            expanded = presetMenuExpanded,
                            onDismissRequest = { presetMenuExpanded = false },
                        ) {
                            state.replyModelPresets.forEach { preset ->
                                SettingsDropdownItem(
                                    label = "${preset.name} · ${preset.settings.model}",
                                    selected = activePreset?.id == preset.id,
                                    onClick = {
                                        presetMenuExpanded = false
                                        onSelectPreset(preset.id)
                                    },
                                )
                            }
                            if (state.replyModelPresets.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFFDCE6F5))
                            }
                            DropdownMenuItem(
                                modifier = Modifier
                                    .heightIn(min = 38.dp, max = 38.dp)
                                    .padding(horizontal = 6.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                text = { Text("模型设置", color = Color(0xFF17233C), fontSize = 12.sp) },
                                leadingIcon = { Text("⚙", color = Color(0xFF1677E8), fontSize = 12.sp) },
                                onClick = {
                                    presetMenuExpanded = false
                                    onOpenReplyModelSettings()
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.width(32.dp))
                    Text(
                        "当前包含 ${state.replyModelPresets.size} 个预设模型",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = Color(0xFF8392B0),
                    )
                }
            }

            Spacer(Modifier.size(8.dp))

            SettingsCard(shape = cardShape, cardPadding = 10.dp, itemSpacing = 4.dp) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpenTokenUsage),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(symbol = "▥", tint = Color(0xFF377DEE), background = Color(0xFFEAF2FF))
                    Column(Modifier.weight(1f)) {
                        Text("Token 使用统计", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("Jev · 理解模型 · 用量走势", color = SECONDARY_TEXT, fontSize = 11.sp)
                    }
                    Text("›", color = SECONDARY_TEXT, fontSize = 24.sp)
                }
            }

            SettingsCard(shape = cardShape, cardPadding = 6.dp, itemSpacing = 5.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(symbol = "▤", tint = Color(0xFF28A878), background = Color(0xFFE4F7EE))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("实时解析", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp), fontWeight = FontWeight.SemiBold)
                        Text("只解析对方新发的普通消息", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = SECONDARY_TEXT)
                    }
                    Switch(
                        checked = state.realtime.enabled,
                        onCheckedChange = { enabled -> if (enabled) onRequestEnableRealtime() else onDisableRealtime() },
                    )
                }
            }
        }
    }

    if (state.showRealtimeConsent) {
        AlertDialog(
            onDismissRequest = onCancelEnableRealtime,
            title = { Text("开启实时解析？") },
            text = {
                Text("开启后，符合条件的对方消息会先在本地处理，再发送到当前配置的模型生成解析。应用不会自动回复或发送消息。")
            },
            confirmButton = { TextButton(onClick = onConfirmEnableRealtime) { Text("开启") } },
            dismissButton = { TextButton(onClick = onCancelEnableRealtime) { Text("暂不开启") } },
        )
    }

}

@Composable
private fun SettingsCard(
    shape: RoundedCornerShape,
    cardPadding: Dp = 8.dp,
    itemSpacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            content = content,
        )
    }
}

@Composable
private fun IconBadge(symbol: String, tint: Color, background: Color) {
    Box(
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = tint, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun JevRobotMark() {
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(
                Brush.radialGradient(listOf(Color(0xFFE0EDFF), Color(0xFFF2F6FC))),
            ),
        )
        Box(
            modifier = Modifier.padding(top = 7.dp).size(width = 44.dp, height = 38.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF263A70)),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Color(0xFF65B5FF), CircleShape))
                Box(Modifier.size(6.dp).background(Color(0xFF65B5FF), CircleShape))
            }
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 0.dp)
                .size(width = 25.dp, height = 18.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { Box(Modifier.size(3.dp).background(Color(0xFF86B6FF), CircleShape)) }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(width = 18.dp, height = 6.dp).background(Color(0xFFB5D1FC), CircleShape))
        Box(Modifier.align(Alignment.CenterStart).size(width = 5.dp, height = 15.dp).background(Color(0xFF9FC5FF), RoundedCornerShape(8.dp)))
        Box(Modifier.align(Alignment.CenterEnd).size(width = 5.dp, height = 15.dp).background(Color(0xFF9FC5FF), RoundedCornerShape(8.dp)))
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val PAGE_BACKGROUND = Color(0xFFF7F8FC)
private val BORDER = Color(0xFFDDE3ED)
private val SECONDARY_TEXT = Color(0xFF7F8795)
private val BLUE_START = Color(0xFF246EED)
private val BLUE_END = Color(0xFF3299F6)
