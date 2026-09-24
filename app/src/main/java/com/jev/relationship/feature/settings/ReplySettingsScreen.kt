package com.jev.relationship.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

@Composable
fun ReplySettingsScreen(
    state: SettingsUiState,
    onBaseUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onSave: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onBack: () -> Unit,
) {
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var showSavePresetDialog by rememberSaveable { mutableStateOf(false) }
    var showDeletePresetDialog by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    val activeReplySettings = remember(state.settings.replyBaseUrl, state.settings.replyApiKey, state.settings.replyModel) {
        runCatching {
            state.settings.replySettings().let { it.copy(baseUrl = it.normalizedBaseUrl()) }
        }.getOrNull()
    }
    val activePreset = state.replyModelPresets.firstOrNull { it.settings == activeReplySettings }

    Scaffold(
        containerColor = PageBackground,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PageBackground)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 41.dp),
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.horizontalGradient(listOf(BlueStart, BlueEnd))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▣   ${if (state.saved) "已保存" else "保存理解模型配置"}", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.height(20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            ) {
                Text("‹  返回", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Row(
                modifier = Modifier.fillMaxWidth().offset(y = (-6).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("理解模型配置", color = Ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "配置理解模型，用于对方消息生成详细回复",
                        color = MutedText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                ModelCubeMark(modifier = Modifier.size(72.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                color = Color.White.copy(alpha = 0.94f),
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge("⬡", Purple, Color(0xFFF0ECFF), size = 32.dp)
                        Text("已保存的模型预设", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(36.dp).clip(FieldShape)
                                    .clickable(role = Role.Button) { presetMenuExpanded = true },
                                shape = FieldShape,
                                color = Color(0xFFFCFDFF),
                                border = BorderStroke(1.dp, Border),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        activePreset?.name ?: if (state.replyModelPresets.isEmpty()) "暂无预设" else "选择预设",
                                        color = Ink,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                    )
                                    Text("⌄", color = MutedText, fontSize = 20.sp)
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
                            }
                        }
                        OutlinedButton(
                            onClick = { showDeletePresetDialog = true },
                            enabled = activePreset != null,
                            modifier = Modifier.height(36.dp),
                            shape = FieldShape,
                            border = BorderStroke(1.dp, Color(0xFFD8E8FF)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                        ) {
                            Text("▤  删除", color = Blue, fontSize = 13.sp)
                        }
                    }
                    Button(
                        onClick = { presetName = ""; showSavePresetDialog = true },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0F7FF)),
                        enabled = state.settings.replySettings().isConfigured,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BookmarkIcon(Modifier.size(width = 13.dp, height = 17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("将当前配置保存为预设", color = Blue, fontSize = 13.sp)
                        }
                    }
                    state.presetMessage?.let { Text(it, color = Blue, fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(5.dp))
            ModelInputCard(
                icon = "↗",
                tint = Blue,
                iconBackground = Color(0xFFEAF3FF),
                label = "理解模型 API Base URL",
                value = state.settings.replyBaseUrl,
                placeholder = "https://api.example.com/v1",
                onValueChange = onBaseUrlChanged,
            )
            Spacer(Modifier.height(5.dp))
            ModelInputCard(
                icon = "⚿",
                tint = Purple,
                iconBackground = Color(0xFFF0ECFF),
                label = "理解模型 API Key",
                value = state.settings.replyApiKey,
                placeholder = "输入 API Key",
                onValueChange = onApiKeyChanged,
                isSecret = true,
                showSecret = showApiKey,
                onToggleSecret = { showApiKey = !showApiKey },
            )
            Spacer(Modifier.height(5.dp))
            ModelInputCard(
                icon = "⬡",
                tint = Green,
                iconBackground = Color(0xFFE6F8F0),
                label = "理解模型名称",
                value = state.settings.replyModel,
                placeholder = "qwen3.7-flash-2026-07-15",
                onValueChange = onModelChanged,
            )

            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("保存模型预设") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSavePreset(presetName)
                        showSavePresetDialog = false
                    },
                    enabled = presetName.isNotBlank() && state.settings.replySettings().isConfigured,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSavePresetDialog = false }) { Text("取消") } },
        )
    }

    if (showDeletePresetDialog && activePreset != null) {
        AlertDialog(
            onDismissRequest = { showDeletePresetDialog = false },
            title = { Text("删除模型预设") },
            text = { Text("确定删除“${activePreset.name}”吗？当前正在使用的配置不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePreset(activePreset.id)
                        showDeletePresetDialog = false
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeletePresetDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun BookmarkIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.08f)
            lineTo(size.width * 0.88f, size.height * 0.08f)
            lineTo(size.width * 0.88f, size.height * 0.92f)
            lineTo(size.width * 0.5f, size.height * 0.68f)
            lineTo(size.width * 0.12f, size.height * 0.92f)
            close()
        }
        drawPath(path, color = Blue, style = Stroke(width = 1.8.dp.toPx()))
    }
}

@Composable
private fun ModelInputCard(
    icon: String,
    tint: Color,
    iconBackground: Color,
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isSecret: Boolean = false,
    showSecret: Boolean = false,
    onToggleSecret: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon, tint, iconBackground, size = 32.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Surface(
                    shape = FieldShape,
                    color = Color(0xFFFCFDFF),
                    border = BorderStroke(1.dp, Border),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Ink),
                            cursorBrush = SolidColor(Blue),
                            visualTransformation = if (isSecret && !showSecret) PasswordVisualTransformation() else VisualTransformation.None,
                            decorationBox = { innerTextField ->
                                Box {
                                    if (value.isEmpty()) Text(placeholder, color = MutedText, fontSize = 13.sp, maxLines = 1)
                                    innerTextField()
                                }
                            },
                        )
                        if (isSecret && onToggleSecret != null) {
                            TextButton(
                                onClick = onToggleSecret,
                                modifier = Modifier.size(32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            ) { Text(if (showSecret) "◉" else "◎", color = MutedText, fontSize = 16.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBadge(symbol: String, tint: Color, background: Color, size: androidx.compose.ui.unit.Dp = 42.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = tint, fontSize = (size.value * 0.43f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModelCubeMark(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape).background(
                Brush.radialGradient(listOf(Color(0xFFE8EFFF), Color(0xFFF7F9FE))),
            ),
        )
        Canvas(modifier = Modifier.size(56.dp)) {
            drawModelCube(this)
        }
    }
}

private fun drawModelCube(scope: DrawScope) = with(scope) {
    val w = size.width
    val h = size.height
    fun path(vararg points: Pair<Float, Float>) = Path().apply {
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(w * x, h * y) else lineTo(w * x, h * y)
        }
        close()
    }
    drawPath(path(0.5f to 0.04f, 0.91f to 0.27f, 0.5f to 0.5f, 0.09f to 0.27f), Color(0xFFC2B5FF))
    drawPath(path(0.09f to 0.27f, 0.5f to 0.5f, 0.5f to 0.96f, 0.09f to 0.73f), Color(0xFF7755E8))
    drawPath(path(0.5f to 0.5f, 0.91f to 0.27f, 0.91f to 0.73f, 0.5f to 0.96f), Color(0xFF9B87F4))
    drawPath(path(0.5f to 0.04f, 0.91f to 0.27f, 0.5f to 0.5f, 0.09f to 0.27f), Color.White.copy(alpha = 0.4f), style = Stroke(width = 1.5.dp.toPx()))
}

private val PageBackground = Color(0xFFF7F8FC)
private val Ink = Color(0xFF17233C)
private val MutedText = Color(0xFF7F8BA2)
private val Border = Color(0xFFDDE4F0)
private val Blue = Color(0xFF1677E8)
private val BlueStart = Color(0xFF126BEE)
private val BlueEnd = Color(0xFF33A5EF)
private val Purple = Color(0xFF7956E8)
private val Green = Color(0xFF16A875)
private val CardShape = RoundedCornerShape(22.dp)
private val FieldShape = RoundedCornerShape(16.dp)
