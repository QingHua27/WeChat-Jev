package com.jev.relationship.service

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jev.relationship.domain.inline.InlineAnalysisCard

@Composable
fun InlineAnalysisCardView(
    card: InlineAnalysisCard,
    onOpen: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 210.dp)
            .testTag("inline_analysis_card")
            .overlayDragGesture(
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onClick = onOpen,
            ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Jev",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("理解结果", style = MaterialTheme.typography.labelSmall)
            }
            val detail = card.output.analysis.detailed
            Text(detail.summary.ifBlank { "基础判断：${card.output.analysis.emotion}" }, style = MaterialTheme.typography.bodySmall)
            Text("对方想要：${detail.intention.ifBlank { "暂未识别" }}", style = MaterialTheme.typography.bodySmall)
            if (detail.evidence.isNotEmpty()) {
                Text("依据：${detail.evidence.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
            }
            Text("建议行动：${detail.action.ifBlank { card.output.analysis.suggestion }}", style = MaterialTheme.typography.bodySmall)
            if (detail.reply.isNotBlank()) {
                Text("建议回复：${detail.reply}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
