package com.jev.relationship.feature.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jev.relationship.data.usage.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private val JevBlue = Color(0xFF377DEE)
private val ModelPurple = Color(0xFF9262D8)
private val UsageBackground = Color(0xFFF5F7FD)
private val Ink = Color(0xFF17233C)
private val Muted = Color(0xFF6E7B92)
data class UsageLoadState(val events: List<TokenUsageEvent> = emptyList(), val loading: Boolean = true, val error: Boolean = false)

@HiltViewModel
class TokenUsageViewModel @Inject constructor(private val store: TokenUsageStore) : ViewModel() {
    fun observe(window: UsageWindow): Flow<UsageLoadState> = store.observe(window.start, window.end)
        .map { UsageLoadState(it, loading = false) }
        .onStart { emit(UsageLoadState()) }
        .catch { emit(UsageLoadState(loading = false, error = true)) }
        .flowOn(Dispatchers.Default)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenUsageRoute(onBack: () -> Unit, viewModel: TokenUsageViewModel = hiltViewModel()) {
    var period by rememberSaveable { mutableStateOf(UsagePeriod.DAY) }
    var unit by rememberSaveable { mutableStateOf(TokenUnit.TOKEN) }
    var chosenDate by rememberSaveable { mutableStateOf<String?>(null) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    val date = chosenDate?.let(LocalDate::parse) ?: today
    val window = remember(period, date) { usageWindow(period, date, ZoneId.systemDefault()) }
    var retry by remember { mutableIntStateOf(0) }
    val state by remember(window, retry) { viewModel.observe(window) }.collectAsStateWithLifecycle(UsageLoadState())
    var filter by rememberSaveable { mutableStateOf<UsageSource?>(null) }
    var selected by remember(window) { mutableStateOf<Int?>(null) }
    val buckets = remember(state.events, window) { usageBuckets(state.events, window) }
    val shownEvents = state.events.filter { filter == null || it.source == filter?.name }
    val totals = usageTotals(shownEvents)

    var detailSource by remember { mutableStateOf<UsageSource?>(null) }
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
    Column(Modifier.fillMaxSize().background(UsageBackground).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp, 40.dp).clickable(onClick = onBack)
                .semantics { contentDescription = "返回设置" }, contentAlignment = Alignment.CenterStart) {
                Text("‹", color = JevBlue, fontSize = 27.sp)
            }
            Text("Token 使用统计", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            CalendarMark()
            Text("本机记录", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
            .padding(top = 4.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    UsagePeriod.entries.forEach { value ->
                        Box(Modifier.weight(1f).heightIn(min = 28.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (period == value) JevBlue else Color(0xFFF0F2F7))
                            .border(1.dp, if (period == value) Color.Transparent else Color(0xFFE6E9F0), RoundedCornerShape(7.dp))
                            .selectable(period == value, role = Role.Tab, onClick = { period = value; selected = null }),
                            contentAlignment = Alignment.Center) {
                            Text(value.label, color = if (period == value) Color.White else Ink, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    DateArrow("‹", "上一时间段", true) { chosenDate = shiftUsageDate(date, period, -1).toString() }
                    Box(Modifier.weight(1f).heightIn(min = 30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.75f))
                        .clickable { chosenDate = null }.semantics { contentDescription = "${window.title}，点按回到当前" }, contentAlignment = Alignment.Center) {
                        Text(window.title, color = Ink, fontSize = if (period == UsagePeriod.WEEK) 8.sp else 10.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                    DateArrow("›", "下一时间段", window.end <= today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) {
                        chosenDate = shiftUsageDate(date, period, 1).toString()
                    }
                }
            }
            UsagePanel(tint = Color(0xFFF4F7FD)) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Text("已报告总用量", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(unit.format(totals.total), color = Ink, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(max = 220.dp))
                        Text(unit.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        RequestMetric(totals.requests, "请求次数", Modifier.weight(1.1f))
                        RequestMetric(totals.requests - totals.failed, "完成次数", Modifier.weight(1f))
                        RequestMetric(totals.failed, "未完成次数", Modifier.weight(0.9f))
                    }
                    Spacer(Modifier.height(16.dp))
                    ChoiceRow(TokenUnit.entries, unit, { it.label }) { unit = it }
                    Spacer(Modifier.height(10.dp))
                    ChoiceRow(listOf<UsageSource?>(null, UsageSource.JEV, UsageSource.UNDERSTANDING), filter,
                        { it?.label ?: "全部" }) { filter = it }
                }
            }
            UsagePanel {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("用量走势", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("●  Jev", color = JevBlue, fontSize = 10.sp)
                        Spacer(Modifier.width(14.dp))
                        Text("●  理解模型", color = ModelPurple, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        UsageBars(buckets, filter, unit, selected) { if (shownEvents.isNotEmpty()) selected = it }
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            state.error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("统计读取失败", color = Muted, fontSize = 11.sp)
                                TextButton(onClick = { retry++ }) { Text("重试") }
                            }
                            shownEvents.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyChartMark()
                                Text("暂无数据", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp, bottom = 4.dp))
                                Text("此时段暂无记录，后续模型请求会自动计入。", color = Muted, fontSize = 9.sp)
                            }
                            totals.total == 0L && totals.unreported > 0 -> Text("接口未报告用量，不代表没有消耗", color = Muted, fontSize = 10.sp)
                        }
                    }
                    selected?.let { index -> buckets.getOrNull(index)?.let { bucket ->
                        Text("${bucket.label}  ·  Jev ${unit.format(bucket.total(UsageSource.JEV))}  /  理解模型 ${unit.format(bucket.total(UsageSource.UNDERSTANDING))} ${unit.label}",
                            color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UsageSource.entries.forEach { source ->
                    SourceSummary(source, usageTotals(state.events, source), unit, Modifier.weight(1f)) { detailSource = source }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0xFFF0F5FE))
                .padding(horizontal = 13.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(JevBlue)
                    .semantics { contentDescription = "统计说明" }, contentAlignment = Alignment.Center) {
                    Text("i", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Text("从本功能启用后开始记录，仅统计本机请求。\n使用接口返回的 Token 用量，历史消耗无法补记。\n未报告的用量不估算。缓存命中不会新增消耗，免费模型也会记录 Token。此处不是费用账单。",
                    color = Muted, fontSize = 8.5.sp, lineHeight = 14.sp)
            }
        }
    }
    detailSource?.let { source ->
        val events = state.events.filter { it.source == source.name }
        val sourceTotals = usageTotals(events)
        AlertDialog(onDismissRequest = { detailSource = null }, title = { Text("${source.label} · 模型明细") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${sourceTotals.requests} 次请求 · ${sourceTotals.unreported} 次总量未报告", fontSize = 13.sp)
                    if (events.isEmpty()) Text("此时段暂无记录", color = Muted)
                    events.groupBy { it.model }.forEach { (model, modelEvents) ->
                        val modelTotals = usageTotals(modelEvents)
                        Text(model, fontWeight = FontWeight.SemiBold)
                        Text("${unit.format(modelTotals.total)} ${unit.label} · ${modelTotals.requests} 次请求", color = Muted)
                    }
                    if (sourceTotals.missingBreakdown > 0) Text("${sourceTotals.missingBreakdown} 次请求未返回完整输入/输出明细，仅汇总已报告部分。", color = Muted, fontSize = 12.sp)
                }
            }, confirmButton = { TextButton(onClick = { detailSource = null }) { Text("关闭") } })
    }
    }
}

@Composable
private fun UsagePanel(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(tint, Color.White.copy(alpha = 0.93f), tint.copy(alpha = 0.55f))))
        .border(1.dp, Color.White.copy(alpha = 0.95f), RoundedCornerShape(16.dp))) { content() }
}

@Composable
private fun DateArrow(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(20.dp, 36.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) JevBlue else JevBlue.copy(alpha = 0.45f), fontSize = 19.sp)
    }
}

@Composable
private fun RequestMetric(count: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(count.toString(), color = Ink, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, choose: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value ->
            Box(Modifier.clip(RoundedCornerShape(7.dp)).background(if (selected == value) Color(0xFFE4EDFE) else Color.Transparent)
                .selectable(selected == value, role = Role.Tab, onClick = { choose(value) })
                .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label(value), color = if (selected == value) JevBlue else Ink, fontSize = 11.sp,
                    fontWeight = if (selected == value) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SourceSummary(source: UsageSource, totals: UsageTotals, unit: TokenUnit, modifier: Modifier, onClick: () -> Unit) {
    val color = if (source == UsageSource.JEV) JevBlue else ModelPurple
    UsagePanel(modifier.clickable(onClick = onClick), if (source == UsageSource.JEV) Color(0xFFEDF4FF) else Color(0xFFF4EEFF)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("●  ${source.label}", color = color, fontWeight = FontWeight.Medium, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("›", color = Muted, fontSize = 18.sp)
            }
            if (totals.requests > 0 && totals.unreported == totals.requests) {
                Text("用量未报告", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(unit.format(totals.total), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                Text(unit.label, color = Ink, fontSize = 11.sp)
            }
            Text("输入 ${unit.format(totals.input)}  /  输出 ${unit.format(totals.output)}", color = Muted, fontSize = 9.sp)
            HorizontalDivider(color = Color(0xFFE4E8F1), modifier = Modifier.padding(vertical = 2.dp))
            Text("${totals.requests} 次请求 · ${totals.failed} 次未完成", color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun CalendarMark() {
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.2.dp.toPx()
        drawRoundRect(JevBlue, Offset(stroke, stroke * 2), Size(size.width - stroke * 2, size.height - stroke * 3),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(JevBlue, Offset(stroke, size.height * 0.45f), Offset(size.width - stroke, size.height * 0.45f), stroke)
        listOf(0.3f, 0.7f).forEach { x -> drawLine(JevBlue, Offset(size.width * x, 0f), Offset(size.width * x, stroke * 3), stroke) }
    }
}

@Composable
private fun EmptyChartMark() {
    Canvas(Modifier.size(20.dp, 18.dp)) {
        listOf(0.55f, 1f, 0.7f).forEachIndexed { index, height ->
            drawRoundRect(Color(0xFFD6DAE5), Offset(index * size.width / 3, size.height * (1 - height)),
                Size(size.width / 4, size.height * height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
private fun UsageBars(buckets: List<UsageBucket>, filter: UsageSource?, unit: TokenUnit, selected: Int?, onSelect: (Int) -> Unit) {
    val sources = filter?.let { listOf(it) } ?: UsageSource.entries
    val reportedMax = buckets.maxOfOrNull { bucket -> sources.maxOf { bucket.total(it) } } ?: 0L
    val maxValue = if (reportedMax > 0L) reportedMax else (400 * unit.divisor).toLong()
    val axisWidth = (unit.format(maxValue).length * 4 + 6).coerceAtLeast(18).dp
    val selectLatest by rememberUpdatedState(onSelect)
    Canvas(Modifier.fillMaxWidth().height(151.dp)
        .semantics { contentDescription = "Token 消耗柱状图，蓝色 Jev，紫色理解模型，可点按或拖动查看时段明细" }
        .pointerInput(buckets.size, axisWidth) {
            fun index(x: Float) = (((x - axisWidth.toPx()) / (size.width - axisWidth.toPx() - 8.dp.toPx())) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
            detectTapGestures { selectLatest(index(it.x)) }
        }.pointerInput(buckets.size, axisWidth) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                selectLatest((((change.position.x - axisWidth.toPx()) / (size.width - axisWidth.toPx() - 8.dp.toPx())) * buckets.size).toInt().coerceIn(0, buckets.lastIndex))
            }
        }) {
        val left = axisWidth.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val plotHeight = bottom - top
        val cell = (right - left) / buckets.size
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 7.5.sp.toPx(); color = Muted.toArgb()
        }
        repeat(5) { grid ->
            val y = bottom - plotHeight * grid / 4
            drawLine(Color(0xFFEDF0F6), Offset(left, y), Offset(right, y), 0.5.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(unit.format((maxValue.toDouble() * grid / 4).toLong()), 0f, y + 3.dp.toPx(), paint)
        }
        buckets.forEachIndexed { index, bucket ->
            if (selected == index) {
                drawRect(Color(0x11377DEE), Offset(left + index * cell, top), Size(cell, plotHeight))
                val x = left + (index + 0.5f) * cell
                drawLine(JevBlue.copy(alpha = 0.45f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            }
            val barWidth = cell * 0.7f / sources.size
            sources.forEachIndexed { sourceIndex, source ->
                val height = (bucket.total(source).toDouble() / maxValue * plotHeight).toFloat()
                if (height > 0) drawRect(if (source == UsageSource.JEV) JevBlue else ModelPurple,
                    Offset(left + index * cell + cell * 0.15f + sourceIndex * barWidth, bottom - height),
                    Size((barWidth - 1.dp.toPx()).coerceAtLeast(1f), height))
            }
            if (index % ((buckets.size + 4) / 5) == 0) {
                paint.textAlign = android.graphics.Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(bucket.label, left + (index + 0.5f) * cell, size.height - 6.dp.toPx(), paint)
                paint.textAlign = android.graphics.Paint.Align.LEFT
            }
        }
    }
}
